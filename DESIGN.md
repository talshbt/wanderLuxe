# System Design Document: WanderLuxe Engine

> **Candidate Note:** This document serves as the architectural blueprint for your submission. It is not just a form to fill out; it is an opportunity to demonstrate your ability to reason about complex distributed systems. Please be detailed, justify your trade-offs, and anticipate edge cases.

## 1. Concurrency & Resource Management Strategy

### Thread Pool Configuration

**Executor Implementation:** (e.g., `ThreadPoolTaskExecutor`, `ForkJoinPool`, or `Virtual Threads`)

- **Core Pool Size:** X
- **Max Pool Size:** Y
- **Queue Capacity:** Z
- **Keep-Alive Time:** T

**Architectural Reasoning:**

- **Sizing Formula:** Explain the mathematics behind your choice. Did you use Little's Law ($L = \lambda W$) or the standard formula $N_{threads} = N_{cpu} * U_{cpu} * (1 + W/C)$ (Wait time / Compute time)?
- **Elaboration:** If your target throughput is 500 RPS and the average "Slow Bank" latency is 2 seconds, Little's Law dictates you need 1000 threads in flight. How does this impact memory footprint?
- **Workload Characterization:** Explicitly categorize the workload (I/O Bound vs. CPU Bound). Given the 2-second bank latency, the system is heavily I/O bound.
- **Virtual Threads:** Did you consider Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor`)? Why or why not?
- **Context Switching:** Discuss if your pool size risks thrashing the OS scheduler. If using Platform Threads, a pool size of 2000 might cause excessive context switching overhead compared to Virtual Threads.

### Backpressure & Saturation Handling

- **Rejection Policy:** Which `RejectedExecutionHandler` did you choose?
    - **AbortPolicy:** Throw exception (Fail fast)? This requires the Kafka consumer to handle the exception, perhaps by seeking back the offset.
    - **CallerRunsPolicy:** The consumer thread executes the task itself. This effectively slows down consumption to the rate of processing, naturally propagating backpressure to Kafka (Consumer Lag increases).
    - **DiscardPolicy:** Drop requests (Shed load)? Dangerous for financial transactions.
- **Consumer Lag Strategy:** If the internal queue fills up and you apply backpressure, the Kafka Consumer will stop polling.
- **Rebalance Storms:** If the processing time exceeds `max.poll.interval.ms`, the broker assumes the consumer is dead and triggers a rebalance. How do you tune this configuration to allow for temporary backpressure without destabilizing the consumer group?

## 2. Data Consistency & Integrity

### Inventory Locking Strategy

- **Locking Mechanism:** (e.g., Redis `SETNX` (Pessimistic), Lua Scripts (Atomic), or MongoDB Versioning (Optimistic))
- **Lua Scripting:** Did you use a server-side Lua script to ensure the "Read-Check-Decrement" operation is atomic? (e.g., `if redis.call('get', KEYS[1]) > 0 then ...`).
- **Race Condition Handling:** Walk through a scenario where two distinct instances of the `booking-processor` attempt to book the last room simultaneously.
    - **Scenario:** Instance A reads inventory=1. Instance B reads inventory=1. Both try to decrement. How does your solution guarantee strictly one winner and one loser?
- **Lock Contention:** If a "hot" property receives 10,000 requests/sec, how does your locking strategy affect latency?
- **Optimization:** Did you consider "Inventory Sharding" (splitting 100 rooms into 10 buckets of 10) to reduce lock contention? Or "Batch Reservation" where one thread reserves 50 spots in memory?

### Distributed Transactions (Saga Pattern)

Since we cannot use ACID transactions across Redis, REST APIs, and MongoDB, we implement a Saga.

- **Saga Type:** Choreography (Event-based) or Orchestration (Centralized)?
- **Compensation Logic:** Describe the "Unhappy Path" in detail.
    - **Scenario:** Inventory Reserved (Redis) ✅ -> Bank Charge Failed (HTTP 402) ❌.
    - **Recovery:** How do you ensure the inventory is released? You must execute a compensating transaction (Increment Redis).
    - **Double Failure:** What happens if the Release operation also fails (e.g., Redis blips)? Do you use a retry topic? A background reconciler job?
- **State Machine:** Do you persist the state of the transaction (e.g., `PENDING`, `RESERVED`, `PAID`, `FAILED`)?
- **Ambiguous State:** How do you handle a "Read Timeout" from the bank? You don't know if the user was charged or not. Do you assume success or failure?

### Idempotency Implementation

- **Key Storage:** Where do you store the `Idempotency-Key`? (Redis vs. Mongo).
- **TTL Strategy:** How long do you keep the key?
- **Risk:** If the key expires after 24 hours, can a replay attack occur on hour 25?
- **Storage Costs:** Storing every UUID forever is expensive. What is your retention policy?
- **The "Check-Then-Act" Race:** How do you ensure that two concurrent requests with the same Idempotency Key don't both pass the check simultaneously?
    - **Solution:** Do you use `SETNX` (Set if Not Exists) to atomically claim the key before processing?

## 3. Scalability & Performance

### Horizontal Scaling

- **Statelessness:** Is the `booking-processor` truly stateless? If it holds local locks or in-memory queues, how does that affect scaling?
- **Kafka Partitions:** How does the number of Kafka partitions relate to your max consumer count?
    - **Constraint:** You cannot have more active consumers than partitions. If you need 50 instances to handle the load, you need at least 50 partitions.
- **Rebalancing Impact:** Describe the impact on system throughput during a deployment (rolling restart). When consumers leave and rejoin, Kafka stops delivery briefly (Stop-the-World). How does your system recover from this pause?

### Database & Storage Bottlenecks

- **Write Throughput:** MongoDB can become a bottleneck under heavy write load.
- **Write Concern:** Did you utilize `w=1` (Fast, risky) or `w=majority` (Safe, slower)?
- **Hot Keys:** How do you handle the "Hot Key" problem in Redis if 90% of traffic targets a single propertyId?
- **Redis Clustering:** Does the hot key pin all traffic to a single Redis shard?

## 4. Resilience & Failure Modes

Please analyze how your system behaves under the following failure scenarios.

### Component Failure

| Component | Impact Analysis | Mitigation Strategy |
| :--- | :--- | :--- |
| **Redis Outage** | Does the system stop accepting bookings (CP) or default to "Sold Out" (Availability)? | (e.g., Sentinel failover, local cache fallback, or circuit breaking to read-only mode) |
| **Payment Gateway Timeout** | The bank takes 30s instead of 2s. | (e.g., Aggressive Timeouts (2s), Retries with Exponential Backoff, Jitter to prevent thundering herds on retry) |
| **Poison Pill Message** | A malformed Kafka message crashes the consumer loop. | (e.g., Dead Letter Queue (DLQ), retry counter header. Do not block the partition forever!) |
| **Kafka Broker Failure** | The leader for the partition goes down. | (e.g., `acks=all`, `min.insync.replicas` configuration to prevent data loss) |
| **MongoDB Slow Write** | Database latency spikes to 500ms. | (e.g., Asynchronous writing, Buffer implementation, or graceful degradation) |

## 5. Observability & Operations

- **Metrics:** What are the Top 3 "Golden Signals" (Latency, Traffic, Errors, Saturation) you would alert on?
    - *Example:* "Inventory Lock Contention Rate" or "Saga Compensation Failure Rate".
- **Tracing:** How do you correlate logs from the `payment-gateway` (Producer) to the `booking-processor` (Consumer)?
    - **Implementation:** Are you propagating `TraceID` and `SpanID` via Kafka Headers?
- **Runbook:** If the system accidentally oversells a room (due to a bug), what is the manual remediation process?
- **Admin Tooling:** Do you have a script to identify oversold bookings and issue refunds?

## 6. System Interface Specifications

### 6.1 API Schema (Payment Gateway)

**Endpoint:** `POST /api/v1/bookings`

**Description:** Initiates a booking request. Returns immediately with `202 Accepted` if the request is valid and queued.

**Request Body:**

```json
{
  "bookingId": "b-123456789",
  "propertyId": "prop-dubai-royal",
  "userId": "user-98765",
  "amount": 5000.00,
  "currency": "USD",
  "checkInDate": "2025-12-24",
  "checkOutDate": "2025-12-27"
}
```

**Headers:**
- `Idempotency-Key`: `uuid-v4-string` (Required)
- `Content-Type`: `application/json`

**Responses:**
- `202 Accepted`: Request queued successfully.
- `400 Bad Request`: Invalid payload (e.g., missing fields, negative amount).
- `409 Conflict`: Idempotency key conflict (request already processed).
- `503 Service Unavailable`: Kafka is down or backpressure limits reached.

---

### 6.2 Kafka Message Schema

**Topic:** `booking.requests`

**Key:** `propertyId` (Ensures ordering per property)

**Value (JSON):**

```json
{
  "eventId": "evt-112233",
  "eventType": "BOOKING_INITIATED",
  "timestamp": "2025-12-16T10:00:00Z",
  "payload": {
    "bookingId": "b-123456789",
    "propertyId": "prop-dubai-royal",
    "userId": "user-98765",
    "amount": 5000.00,
    "currency": "USD",
    "dates": {
      "checkIn": "2025-12-24",
      "checkOut": "2025-12-27"
    }
  },
  "metadata": {
    "traceId": "trace-abc-123",
    "spanId": "span-xyz-789",
    "origin": "payment-gateway"
  }
}
```

---

### 6.3 MongoDB Schema

**Collection:** `bookings`

**Document Structure:**

```json
{
  "_id": "b-123456789",
  "userId": "user-98765",
  "propertyId": "prop-dubai-royal",
  "status": "CONFIRMED",  // Enum: PENDING, RESERVED, CONFIRMED, FAILED, CANCELLED
  "payment": {
    "amount": 5000.00,
    "currency": "USD",
    "transactionId": "tx-bank-001",
    "status": "CAPTURED"
  },
  "auditLog": [
    {
      "action": "RECEIVED",
      "timestamp": "2025-12-16T10:00:00Z"
    },
    {
      "action": "INVENTORY_RESERVED",
      "timestamp": "2025-12-16T10:00:01Z"
    },
    {
      "action": "PAYMENT_SUCCESS",
      "timestamp": "2025-12-16T10:00:03Z"
    }
  ],
  "createdAt": "2025-12-16T10:00:00Z",
  "updatedAt": "2025-12-16T10:00:03Z",
  "version": 1 // Optimistic locking version
}
```

**Indexes:**
- `db.bookings.createIndex({ "propertyId": 1, "status": 1 })`
- `db.bookings.createIndex({ "userId": 1 })`
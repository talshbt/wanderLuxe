# WanderLuxe: Distributed Booking Engine Challenge

## 1. Overview & Business Context

**WanderLuxe** is a high-end travel platform specializing in "Flash Sales" where demand vastly exceeds supply (e.g., “50% off the Royal Suite in Dubai — Only 3 nights available”). This repository contains the reference solution for the **Senior Backend Architect Challenge**.

### The Business Problem
In a standard e-commerce system, a slightly delayed inventory update is acceptable. However, for luxury flash sales, selling 4 rooms when you only have 3 is a catastrophic customer experience involving manual cancellations and reputation loss. The "Thundering Herd" problem—where thousands of users hit the "Buy" button at the exact same millisecond—exposes race conditions that standard ACID transactions in a monolithic database cannot handle at scale.

### The Technical Goal
Build a robust, distributed system capable of handling massive concurrency spikes. The system must:

1.  **Strictly Enforce Inventory Limits:** Eliminate the "Time-of-Check to Time-of-Use" (TOCTOU) race condition.
2.  **Guarantee Financial Consistency:** Ensure users are never charged twice, even in the event of network partitions or service crashes.
3.  **Maintain High Throughput:** Decouple the user-facing API latency from downstream dependencies (like slow banking systems), allowing the ingestion layer to accept thousands of requests per second even if the processing layer is backlogged.

### Senior Developer Expectations
This exercise is designed to test advanced backend concepts including:
-   **Distributed Locking Strategies:** Comparing pessimistic locking (Redis) vs. optimistic locking (Versioning) in a microservices environment.
-   **Asynchronous Event Processing:** Managing offset commits and consumer lag when decoupling ingestion from heavy processing.
-   **Fault Tolerance & Sagas:** Implementing "Saga Lite" patterns to handle partial system failures gracefully without using heavy 2-Phase Commit (2PC) protocols.
-   **Clean Code & Architecture:** Using proper design patterns, separation of concerns, and comprehensive testing.

---

## 2. Architecture

The system follows an **Event-Driven Microservices Architecture**, decoupled via Apache Kafka. This ensures that the ingestion layer remains responsive even if the processing layer is under heavy load (Load Leveling).

| Service | Type | Port | Responsibility & Design Patterns |
| :--- | :--- | :--- | :--- |
| **payment-gateway** | REST API | `8080` | **The Ingress Point.** It handles authentication (mock), deep payload validation, and acts as the Kafka Producer.<br><br>**Design:** It implements the **Fire-and-Forget** pattern for the client but uses **Synchronous Send** to Kafka (`acks=all`) internally to ensure durability before returning `202 Accepted`. It does not wait for the booking to be confirmed. |
| **booking-processor** | Worker | — | **The Core Engine.** It consumes events from Kafka, manages distributed locks in Redis to secure inventory, executes the "Slow Bank" simulation, and persists the final ledger to MongoDB.<br><br>**Design:** It implements the **Saga Pattern (Orchestration-based)** to manage the distributed transaction across Redis, the External Bank, and MongoDB. |
| **audit-service** | Worker | — | **The Compliance Consumer.** A demonstration of the **Fan-out** pattern. It listens to the same Kafka topic but belongs to a different Consumer Group (`audit-group`), allowing it to archive raw events for analytics and legal auditing without affecting the throughput or offset management of the main booking flow. |

### Infrastructure Components

-   **Apache Kafka (Event Backbone):**
    -   Acts as the durable buffer between the Gateway and the Processor.
    -   **Partition Strategy:** We partition events by `propertyId`. This ensures that all booking requests for a specific hotel arrive at the same consumer instance in order (though our threading model relaxes this ordering for performance, see Section 4).
    -   **Allows for Load Leveling:** If traffic spikes to 10k RPS, the Gateway accepts it, but the Processor consumes it at a steady 500 RPS without crashing.

-   **Redis (Distributed State & Locking):**
    -   Utilized for high-speed, atomic distributed locking and inventory counters.
    -   **Why Redis?** Its single-threaded nature combined with Lua scripting allows us to perform "Check-and-Decrement" operations atomically, which is impossible with standard database queries at this speed.
    -   **Idempotency:** Also serves as a temporary cache for `Idempotency-Keys` with TTLs to deduplicate rapid retries.

-   **MongoDB (System of Record):**
    -   Stores the permanent booking ledger and transaction history.
    -   **Why Mongo?** The flexible document model allows us to store the booking state, the audit trail of the saga steps, and the raw payment token data in a single document. It supports high-write throughput which is essential during flash sales.

---

## 3. Getting Started

### Prerequisites
-   **Docker & Docker Compose** (Ensure the daemon is running with at least 4GB RAM allocated to prevent OOM kills on the Java containers).
-   **Java 17+** (Required for local development and compilation).
-   **Maven or Gradle** (Build tool).

### Running the System

The entire infrastructure (Zookeeper, Kafka, Redis, Mongo) and the three microservices can be orchestrated with a single command. This ensures a consistent environment for testing and review.

```bash
docker-compose up --build
```

> **Note:** Wait for the containers to stabilize. You may see connection errors initially (e.g., `Connection refused`) as the Java applications attempt to connect to Kafka before it is fully elected as the leader. The apps are configured with retry policies to handle this.

### Seeding Inventory

Before testing, you must initialize the state. We use Redis to hold the "source of truth" for available inventory to allow for atomic decrements.

```bash
# Sets 5 available rooms for the property 'prop-dubai-royal'
# This command connects to the redis container and sets the initial integer value.
docker exec wanderluxe-redis redis-cli set inventory:prop-dubai-royal 5
```

### Triggering a Booking

You can simulate a user booking request using cURL. The `Idempotency-Key` is crucial for testing the system's resilience against duplicate requests.

```bash
# We generate a UUID for the idempotency key to simulate a unique client request
curl -X POST http://localhost:8080/api/v1/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "bookingId": "b-1001",
    "propertyId": "prop-dubai-royal",
    "amount": 5000.00,
    "currency": "USD"
  }'
```

**Expected Response:** `HTTP/1.1 202 Accepted`

---

## 4. Key Implementation Details & Trade-offs

### Concurrency & Threading Strategy
A naive Kafka consumer processes messages sequentially on a single thread. If the "Bank Simulation" takes 2 seconds, the system throughput would drop to 0.5 requests per second per partition. Scaling partitions helps, but it is resource-heavy.

To solve this, the `booking-processor` implements a **Custom ThreadPoolTaskExecutor**:
-   **The Pattern:** The Kafka Consumer thread's only job is to deserialize the message and hand it off to the Executor Service. It then immediately fetches the next message.
-   **The Benefit:** This allows the system to process N bookings in parallel (where N is the thread pool size), effectively utilizing CPU resources while threads are blocked waiting for I/O (the slow bank).
-   **The Trade-off (Complexity):** This breaks standard Kafka ordering guarantees within a partition. However, since inventory decrements are atomic in Redis, ordering is less critical than throughput.
-   **Offset Management Risk:** If the main thread auto-commits the offset but the worker thread crashes before processing is done, the message is lost.
-   **Solution:** We disable `enable.auto.commit` and implement **Manual Acknowledgement**. The offset is only committed once the `CompletableFuture` from the worker thread completes successfully.

### Idempotency & Deduplication
In a distributed system, network timeouts often cause clients to retry requests. Without protection, a user might be charged twice. We utilize a **Check-Then-Act** pattern protected by a distributed lock or atomic write:
1.  **Check:** Does the `Idempotency-Key` exist in Redis (fast path) or MongoDB (slow path)?
2.  **Act:**
    -   **If No:** Create a "Pending" record with this key and proceed with locking and payment.
    -   **If Yes:** Return the previous result immediately.
-   **Race Condition Handling:** If two requests with the same key arrive simultaneously, the database unique constraint or Redis `SETNX` will reject the second one.

This guarantees **"Exactly-Once"** business processing semantics, even though the underlying messaging infrastructure guarantees "At-Least-Once" delivery.

### Resilience & Distributed Transactions (Saga Pattern)
Since we span multiple technologies (Redis, REST Bank API, MongoDB), we cannot use ACID transactions. We implement a **Compensating Transaction Saga**:

**The Happy Path:**
1.  **Reserve:** Redis `DECR inventory:prop-dubai-royal`. (Result: 4).
2.  **Charge:** Call Bank API. (Result: Success).
3.  **Confirm:** Update MongoDB status to `CONFIRMED`.

**The Failure Path (Bank Fails):**
1.  **Reserve:** Redis `DECR inventory:prop-dubai-royal`. (Result: 4).
2.  **Charge:** Call Bank API. (Result: Timeout/Fail).
3.  **Compensate:** We must undo step 1.
    -   **Action:** Redis `INCR inventory:prop-dubai-royal`. (Result: 5).
4.  **Update:** Set MongoDB status to `FAILED_PAYMENT`.

> **Critical Design Note:** The compensation step (INCR) must succeed. If Redis is down during compensation, the system must retry indefinitely (or send to a Dead Letter Queue) to avoid permanently losing inventory ("Leaked Inventory").

---

## 5. Deliverables Checklist

The candidate is expected to complete the following phases:

- [ ] **Phase 1: Infrastructure Setup**
    - [x] Docker Compose file creation.
    - [ ] Kafka Topic auto-creation via Init containers.

- [ ] **Phase 2: Payment Gateway API**
    - [ ] Spring WebFlux or MVC endpoint.
    - [ ] Input Validation (`@Valid`).
    - [ ] KafkaProducer configuration with `acks=all`.

- [ ] **Phase 3: Booking Processor**
    - [ ] KafkaListener setup.
    - [ ] Async Thread Pool implementation.
    - [ ] Redis Lua Script for atomic decrement.
    - [ ] Bank Service Mock (with `Thread.sleep`).

- [ ] **Phase 4: Audit Service**
    - [ ] Simple consumer logging to console/file.

- [ ] **Phase 5: Integration Tests**
    - [ ] End-to-End verification using Testcontainers (Kafka + Redis + Mongo).

---

## 6. Current Project Status & Boilerplate

> **Note to Candidate:** This repository provides the architectural specs and infrastructure definitions, but **no code skeleton**.

*   ✅ **Requirements:** `DESIGN.md` & `README.md` are provided.
*   ✅ **Infrastructure:** `docker-compose.yaml` is ready (services are commented out).
*   ❌ **Code:** No Java/Spring Boot project structure exists. **You are expected to initialize the project structure yourself** (e.g., using Spring Initializr) to demonstrate your ability to set up a multi-module Maven/Gradle project from scratch.
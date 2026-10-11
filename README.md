# Temporal Order Service (Java 21 + Spring Boot)

Companion project for the article **"Durable by Design: Building Unbreakable Workflows with Temporal, Java & Spring Boot"**.

An order-fulfilment **saga** that authorises a payment, reserves stock, waits up to 24 h for a manager's approval on large orders, captures, ships and notifies, and **compensates in reverse order** (release stock, refund payment) when anything fails for good. External systems are simulated in memory, so it runs on your laptop with nothing but Java, Maven and a local Temporal server.

```
POST /orders ──► OrderWorkflow ──► authorize ─► reserve ─► [> 5,000? wait for approve/cancel signal, 24 h max]
                                    ─► capture ─► createShipment ─► notify ─► COMPLETED
                     on failure ──► release stock ─► refund payment ─► FAILED / CANCELLED
```

## 1. Prerequisites

| Tool | Version | Check |
| --- | --- | --- |
| JDK | 21 or newer | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Temporal CLI **or** Docker | latest | `temporal --version` / `docker -v` |

Install the Temporal CLI:
- macOS: `brew install temporal`
- Linux: `curl -sSf https://temporal.download/cli.sh | sh`
- Windows: download from https://temporal.io/setup/install-temporal-cli

## 2. Run the unit tests (no server needed)

```bash
mvn test
```

Six workflow tests run against Temporal's in-memory test server with **time skipping**, so the 24-hour approval timeout is tested in milliseconds:

| Test | Proves |
| --- | --- |
| `smallOrderShipsWithoutApproval` | Happy path, no approval below 5,000 |
| `highValueOrderWaitsForApprovalThenShips` | Durable wait + `approve` signal + `getStatus` query |
| `missingApprovalCompensatesInReverseOrder` | 24 h timer fires, stock released **then** payment refunded |
| `customerCancelWhileAwaitingApproval` | `cancel` signal, compensation, no capture |
| `shippingFailureRefundsAndReleases` | Non-retryable activity failure triggers the saga |
| `declinedPaymentHasNothingToCompensate` | Business error fails fast, no compensation needed |

## 3. Start a local Temporal server

**Option A – Temporal CLI (recommended)**
```bash
temporal server start-dev --db-filename temporal.db
```

**Option B – Docker**
```bash
docker compose up -d
```

Either way: gRPC on `localhost:7233`, Web UI on **http://localhost:8233**.

## 4. Start the application

In a second terminal:
```bash
mvn spring-boot:run
```

You should see the worker start polling `ORDER_TASK_QUEUE`. The REST API listens on `http://localhost:8080`.
To point at another server: `TEMPORAL_TARGET=myhost:7233 mvn spring-boot:run`.

## 5. Try it

### All scenarios in one go
```bash
chmod +x demo.sh
./demo.sh            # or one scenario: ./demo.sh approval
```

### By hand
```bash
# Place a large order -> waits for approval
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{
  "orderId":"42","customerId":"cust-7",
  "lines":[{"sku":"SKU-1","quantity":3}],
  "total":7500,"shippingAddress":"1 Rue X, Casablanca"}'

curl localhost:8080/orders/42                          # query  -> AWAITING_APPROVAL
curl -X POST "localhost:8080/orders/42/approve?by=alice"  # signal
curl localhost:8080/orders/42                          # query  -> COMPLETED + tracking number
```

IntelliJ / VS Code users can run `http/orders.http` instead.

### Built-in failure switches (simulated systems)

| To trigger | Send | Result |
| --- | --- | --- |
| Card declined | `total` above 50000 | `FAILED`, nothing to compensate |
| Out of stock | a SKU starting with `OUT-` | `FAILED`, payment refunded |
| Bad address | `shippingAddress` containing `INVALID` | `FAILED`, stock released then payment refunded |
| Flaky payment provider | `orderId` containing `flaky` | two HTTP 503s, retried automatically, `COMPLETED` |
| Duplicate submit | same `orderId` twice | `409 Conflict` |

## 6. The crash test (the point of Temporal)

1. Place the 7,500 order above. It sits in `AWAITING_APPROVAL`.
2. Stop the app with **Ctrl+C**.
3. Start it again with `mvn spring-boot:run`.
4. Approve it: `curl -X POST "localhost:8080/orders/42/approve?by=alice"`.
5. It captures, ships and completes. No state was lost, because the state lives in the workflow's event history, not in the JVM.

Open http://localhost:8233, click the workflow `order-42` and read its event history: every activity, retry, signal and timer is there.

## Project layout

```
src/main/java/com/acme/orders
├── api/OrderController.java          REST: start workflow, signal, query
├── workflow/OrderWorkflow(Impl).java the saga (deterministic code only)
├── activity/*Activities(Impl).java   side effects, as Spring beans
├── gateway/PaymentGatewayClient.java simulated payment provider (idempotency keys)
└── domain/                           records passed in and out of the workflow
src/test/java/.../OrderWorkflowTest.java  time-skipping workflow tests
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `UNAVAILABLE: io exception` at startup | The Temporal server is not running on `localhost:7233`. Start step 3 first. |
| Workflow stays in *Running* with no progress | The app is not running, so no worker polls the task queue. |
| `Unsupported class file major version` | You are on a JDK older than 21. |
| Port 8080 already in use | `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` |

## Versions

Spring Boot 3.5.6, Temporal Java SDK / Spring Boot starter 1.38.0, Java 21. The starter also supports Spring Boot 4.x: change the parent version in `pom.xml`.

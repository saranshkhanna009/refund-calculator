# Razorpay Refund Calculator + Idempotency Layer

**Interview-ready project demonstrating fintech domain expertise + distributed systems fundamentals.**

---

## 🎯 What This Solves

Real-world Razorpay problem: **Partial returns with mixed payment methods (wallet + online) + platform fees + GST**

| Scenario | Order | Payment Split | Fee | GST | Refund |
|----------|-------|---------------|-----|-----|--------|
| Full return | ₹700 | ₹300 Wallet + ₹400 Online | 2% non-refundable | 18% on online (reversible) | ₹686 (₹294 wallet + ₹392 online) |
| Partial (50%) | ₹350 | Proportional | 2% on ₹350 | 18% on online portion | ₹343 |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    IdempotentRefundService                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Idempotency │  │  Refund     │  │   RefundExecutor    │  │
│  │   Store     │  │ Calculator  │  │  (Gateway/DB/LEDGER)│  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                │                    │
         ▼                ▼                    ▼
   Redis/In-Memory   Pure Java Math      Razorpay API
   (Redisson)        (BigDecimal)        Webhooks
```

---

## 📁 Project Structure

```
src/main/java/com/razorpay/refund/
├── model/
│   ├── Money.java              # Immutable money (BigDecimal, no float bugs)
│   ├── PaymentMethod.java      # WALLET, ONLINE, COD, GIFT_CARD
│   └── PaymentAllocation.java  # Proportional split with rounding fix
├── policy/
│   ├── RefundPolicy.java       # Strategy interface (merchant-configurable)
│   └── StandardRefundPolicy.java # 2% fee, 18% GST, proportional split
├── service/
│   ├── RefundCalculator.java   # Stateless calculation service
│   ├── RefundBreakdown.java    # Rich result object (ledger, tax, UI)
│   ├── IdempotentRefundService.java # Main service with idempotency
│   ├── RefundExecutor.java     # Interface for side effects
│   └── RazorpayRefundExecutor.java  # Production impl (skeleton)
├── idempotency/
│   ├── IdempotencyKey.java     # refund:{orderId}:{razorpayRefundId}
│   ├── IdempotencyStore.java   # Interface (Redis/InMemory)
│   ├── RedisIdempotencyStore.java # Redisson distributed lock
│   └── InMemoryIdempotencyStore.java # Testing
├── webhook/
│   └── RazorpayRefundWebhookHandler.java # Handles Razorpay retries
├── reconciliation/
│   ├── SettlementReport.java         # Razorpay settlement model
│   ├── InternalLedger.java           # Our refund ledger
│   ├── ReconciliationEngine.java     # Matching + discrepancy detection
│   ├── ReconciliationJob.java        # Daily scheduled job (6 AM IST)
│   ├── SettlementReportFetcherImpl.java # SFTP/API fetcher
│   ├── ReconciliationReportGenerator.java # HTML/CSV/JSON reports
│   ├── DiscrepancyHandlerImpl.java   # Severity-based routing
│   └── AutoResolverImpl.java         # Auto-resolve known patterns
└── saga/
    ├── SagaOrchestrator.java           # Step execution + compensation
    ├── SagaContext.java                # Immutable context passed between steps
    ├── SagaStep.java                   # Step interface (execute + compensate)
    ├── steps/
    │   ├── GatewayRefundStep.java      # Step 1: Razorpay refund API
    │   ├── LedgerEntryStep.java        # Step 2: Write ledger entry
    │   ├── WalletCreditStep.java       # Step 3: Credit wallet/cashback
    │   └── NotificationStep.java       # Step 4: Send confirmation
    └── outbox/
        ├── OutboxEvent.java            # Transactional outbox event
        └── OutboxPublisher.java        # Reliable event publishing
```

---

## 🧪 Tests (Run These in Interview)

```bash
mvn test
```

**Key test cases:**
| Test | Verifies |
|------|----------|
| `yourExactScenario_fullReturn` | Your ₹700/₹300/₹400 scenario |
| `partialReturn_halfQuantity` | Partial return math |
| `duplicateRequest_returnsCachedResult` | **Idempotency: no double execution** |
| `failedRequest_canRetryAfterFailure` | Retry after transient error |
| `webhookDuplicateDelivery_returnsCachedResult` | Razorpay webhook retry handling |
| `webhookRetryAfterFailure_succeeds` | End-to-end retry flow |
| `happyPath_allStepsComplete` | Saga: all 4 steps execute |
| `gatewayFailure_noCompensationNeeded` | Saga: first step fails |
| `ledgerFailure_compensatesGateway` | Saga: reverse compensation |
| `walletFailure_compensatesLedgerAndGateway` | Saga: multi-step rollback |
| `noWalletPortion_skipsWalletStep` | Saga: conditional step |
| `publishPendingEvents_publishesAndMarksPublished` | Outbox: reliable publishing |
| `failedPublish_incrementsRetryCount` | Outbox: retry logic |

---

## 💡 Interview Talking Points

### 1. **Money Handling** (Money.java)
> *"Never use `double` for money. `BigDecimal` with `HALF_EVEN` rounding (banker's rounding) prevents 0.1 + 0.2 ≠ 0.3 bugs. Immutable value object enables safe sharing across threads."*

### 2. **Proportional Split with Rounding Fix** (PaymentAllocation.java)
> *"When splitting ₹686 proportionally 30/70, you get ₹294 + ₹392 = ₹686. But with ₹100 split 33.33/66.67, rounding causes ₹1 loss. I absorb the difference into the largest share — total always matches."*

### 3. **Strategy Pattern for Policies** (RefundPolicy.java)
> *"Different merchants need different rules: marketplace vs direct, fee refundable vs not, GST inclusive vs exclusive. Policy interface lets us swap without touching calculator."*

### 4. **Idempotency Key Design** (IdempotencyKey.java)
> *"Key format: `refund:{orderId}:{razorpayRefundId}`. Uses **gateway's refund ID** — critical because Razorpay retries webhooks with same ID. Our key matches their retry semantics exactly."*

### 5. **Distributed Lock with Redisson** (RedisIdempotencyStore.java)
> *"`tryLock(5s, 30s)` — wait 5s for lock, auto-release after 30s. Prevents thundering herd on webhook retries. Double-check pattern: check status AFTER acquiring lock."*

### 6. **Webhook Retry Handling** (RazorpayRefundWebhookHandler.java)
> *"Razorpay retries 3x (10m, 1h, 24h) on 5xx. We return 200 for duplicates (fast path), 500 for failures (triggers retry). Idempotency store status check before processing = zero duplicate refunds."*

### 7. **Failure Recovery**
> *"If process crashes after calculation but before gateway call: lock expires (30s), next retry acquires lock, recalculates (deterministic), executes. At-least-once delivery + idempotent gateway call = exactly-once semantics."*

---

## 🚀 Running Locally

```bash
# Requires: Java 17+, Maven 3.9+
cd refund-calculator
mvn test
```

**Expected output:**
```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

---

## 📚 Razorpay-Specific Knowledge Demonstrated

| Topic | Where Shown |
|-------|-------------|
| Razorpay webhook retry policy | `RazorpayRefundWebhookHandler.java` |
| Idempotency key on Razorpay API | `RazorpayRefundExecutor.java` comments |
| Paise-based amounts | `Money.ofPaise()` |
| GST on online payments only | `StandardRefundPolicy.calculateGst()` |
| Platform fee (MDR) non-refundable | `StandardRefundPolicy.isPlatformFeeRefundable()` |
| Partial refund proportional split | `PaymentAllocation.proportionalSplit()` |
| Wallet vs online refund routing | `RefundBreakdown.getWalletRefund()` / `getMerchantOnlineRefund()` |
| **Settlement cycle (T+2/T+3)** | `ReconciliationJob.java` cron schedule |
| **Settlement report structure** | `SettlementReport.java` model |
| **Reconciliation matching logic** | `ReconciliationEngine.java` |
| **Discrepancy types & severity** | `ReconciliationEngine.Discrepancy` |
| **Auto-resolution patterns** | `AutoResolverImpl.java` |
| **Saga pattern (distributed tx)** | `SagaOrchestrator.java`, `SagaStep.java` |
| **Compensating transactions** | `GatewayRefundStep.compensate()`, etc. |
| **Transactional outbox** | `OutboxEvent.java`, `OutboxPublisher.java` |

---

## 🔄 Reconciliation System (Session 3)

### Problem
Razorpay settles funds **T+2/T+3** (2-3 business days after transaction). We process refunds instantly, but actual money moves days later. Need to verify:
- Every refund we processed → appears in settlement report
- Every settlement entry → exists in our ledger
- Amounts match (net of fees, GST)
- No timing gaps or missing entries

### Solution

| Component | Purpose |
|-----------|---------|
| `SettlementReport` | Models Razorpay settlement CSV/API (payments, refunds, fees, tax, UTR) |
| `InternalLedger` | Our refund records with expected net settlement |
| `ReconciliationEngine` | Matches by Razorpay Refund ID, detects 5 discrepancy types |
| `ReconciliationJob` | Daily @ 6 AM IST (after settlement drops ~4-5 AM) |
| `DiscrepancyHandler` | Routes by severity: CRITICAL→PagerDuty, HIGH→Jira, MEDIUM→Queue, LOW→Auto-resolve |
| `AutoResolver` | Handles rounding diffs, timing diffs, known bank fee variations |

### Discrepancy Types

| Type | Severity | Example |
|------|----------|---------|
| `MISSING_IN_SETTLEMENT` | CRITICAL | We refunded ₹50k, Razorpay didn't settle |
| `MISSING_IN_LEDGER` | HIGH | Settlement has ₹30k refund we never recorded |
| `AMOUNT_MISMATCH` | MEDIUM/HIGH | Expected ₹669.81, got ₹650.00 (fee diff) |
| `STATUS_MISMATCH` | HIGH | We marked FAILED, settlement shows PROCESSED |
| `TIMING_DIFFERENCE` | LOW | Settled in next batch |

### Interview Talking Points

> *"I built a daily reconciliation job that matches our refund ledger against Razorpay T+2 settlement reports. The engine matches by Razorpay Refund ID (rfnd_xxx), detects 5 discrepancy types with severity routing. CRITICAL issues (missing settlement) trigger PagerDuty + Jira instantly. LOW issues (rounding diffs < ₹1) auto-resolve. Reports generated in HTML/CSV/JSON for finance team. This prevents revenue leakage — at scale, even 0.1% missing settlements = lakhs per month."*

---

## 🔄 Saga Pattern (Session 4)

### Problem
A refund spans **4 distributed services** — none supports distributed transactions:
1. **Gateway** (Razorpay) — create refund
2. **Ledger** (DB) — record entry
3. **Wallet** (Internal) — credit cashback
4. **Notification** — send email/SMS

If step 3 fails → must undo steps 1 & 2. **No 2PC, no XA — use Saga with compensating transactions.**

### Solution: Choreographed Saga with Orchestrator

| Step | Service | Forward Action | Compensation | Idempotent? |
|------|---------|----------------|--------------|-------------|
| 1 | Gateway | `POST /v1/refunds` (idempotency key) | Void refund (within 48h) | ✅ |
| 2 | Ledger | `INSERT refund_entry` | Mark `COMPENSATED` | ✅ |
| 3 | Wallet | `credit(wallet, amount, sagaId)` | `debit(walletTxnId)` | ✅ |
| 4 | Notify | `sendRefundConfirmation()` | `sendRefundFailed()` (best effort) | ✅ |

**State Machine:** `CREATED → RUNNING → COMPLETED` / `FAILED → COMPENSATING → COMPENSATED`

### Key Files

| File | Purpose |
|------|---------|
| `SagaOrchestrator.java` | Executes steps sequentially, triggers reverse compensation on failure |
| `SagaStep.java` | Interface: `execute()` + `compensate()` + `isIdempotent()` |
| `SagaContext.java` | Immutable context passed through all steps |
| `GatewayRefundStep.java` | Razorpay API call with idempotency |
| `LedgerEntryStep.java` | DB write with compensation marking |
| `WalletCreditStep.java` | Internal wallet credit/debit |
| `NotificationStep.java` | Non-blocking notification |
| `OutboxEvent.java` | Transactional outbox for saga events |
| `OutboxPublisher.java` | Polls outbox → publishes to Kafka |

### Interview Talking Points

> *"I implemented a saga orchestrator for the refund flow across 4 services. Each step has a forward action and compensating transaction. The orchestrator executes sequentially, persists state at each transition, and on failure walks backwards calling `compensate()` on completed steps. Idempotent steps (gateway, ledger, wallet) are skipped during compensation since they're safe to retry. The outbox pattern ensures saga completion events are reliably published to Kafka for downstream consumers (analytics, loyalty, etc.). This gives us atomicity without distributed locks or 2PC."*

> *"Critical insight: **Idempotency keys at every step**. Gateway uses `refund:{orderId}:{razorpayRefundId}`, ledger uses `sagaId` as unique constraint, wallet uses `sagaId` as idempotency key. This means the entire saga is safely retryable — if the process crashes mid-way, the next retry picks up, re-executes idempotent steps (no-op), and continues."*

---


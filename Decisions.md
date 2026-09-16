# Decision Log

## 1. How did you handle the concurrency race condition?

Two separate mechanisms, for two separate problems:

**Duplicate `transactionId` (idempotency):**
- `transactionId` is the **primary key** of the `transactions` table. The database
  physically cannot store the same id twice — this is the ultimate safety net.
- Before that, `TransactionServiceImpl` uses a `ConcurrentHashMap<UUID, Object>` to
  get one lock object per `transactionId`. All 3 identical concurrent requests
  synchronize on the same lock object, so they execute one at a time. The first
  one through checks "does this transactionId already exist?" — no — processes it.
  The second and third then see it already exists and immediately return the
  cached response as `409 Conflict`, without touching the wallet balance again.

**Simultaneous debits against the same wallet (overdraft prevention):**
- `WalletRepository.findByUserIdForUpdate()` uses
  `@Lock(LockModeType.PESSIMISTIC_WRITE)`, which Hibernate translates into
  `SELECT ... FOR UPDATE`. Wrapped in `@Transactional` (on `WalletTransactionExecutor`,
  a separate bean), this means: when thread A locks a wallet row to check/debit
  the balance, thread B's `SELECT ... FOR UPDATE` for the *same* row physically
  blocks at the database level until A commits. B then reads the balance A just
  left behind, not a stale value. This is what makes the 10-thread test land on
  exactly ₹0 instead of a negative or inconsistent balance.
- We deliberately chose pessimistic locking over optimistic locking
  (`@Version` + retry loop) because with 10 threads hammering one row, optimistic
  locking would produce a lot of retries/conflicts to handle; pessimistic locking
  gives a simpler, more predictable serialization point for this use case.

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

- ** for helping to resolve and understan the root cause of an error **

- **Self-invocation of `@Transactional`:** An early suggestion had the wallet-debit
  logic as a second `@Transactional` method inside the *same* service class that
  also handled the idempotency lock. Spring's `@Transactional` only takes effect
  through its proxy — calling another method on `this` bypasses the proxy
  entirely, so the pessimistic lock/transaction boundary would silently not
  apply, defeating the whole race-condition fix. Fixed by moving the transactional
  wallet logic into its own bean (`WalletTransactionExecutor`) called from the
  service, not from within it.
- **Throwing inside the transactional method for a business failure:** A
  suggested version threw `InsufficientFundsException` directly inside the
  `@Transactional debit()` method after saving a "failed" transaction row.
  Because an uncaught `RuntimeException` triggers a full rollback of everything
  in that transaction — not just the wallet update — the failed-transaction
  row would have been rolled back too, breaking idempotency for retries of that
  same failed request. Fixed by returning a `Transaction` with a `FAILED` status
  instead of throwing, and deciding whether to surface it as an HTTP error one
  layer up, outside the transactional boundary.


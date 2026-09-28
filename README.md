# Spring Boot Transactions

A small bank API that demonstrates how `@Transactional` really behaves: rollback rules,
propagation, isolation, and the classic bugs that make transactions silently not work.

Every pitfall has a demo endpoint. Call it without parameters to see the **bug**, and with
`?fixed=true` to see the **fix**. Each demo opens fresh accounts, so you can run them in any order,
as many times as you like.

## Running

Requires Java 17+.

```bash
./gradlew bootRun
./gradlew test        # 15 tests: one per bug, one per fix, plus the transfer API
```

The app uses an in-memory H2 database and seeds two accounts on startup: **Alice (#1, 1000.00)**
and **Bob (#2, 500.00)**. Data resets on every restart.

If port 8080 is taken, run `./gradlew bootRun --args='--server.port=8081'` and change `BASE` below.

```bash
BASE=http://localhost:8080/api
```

The examples pipe through [`jq`](https://jqlang.org/) for readability; drop `| jq` if you don't have it.

Transaction lifecycle is logged at DEBUG, so watch the app console while calling the demos —
you'll see lines like `Creating new transaction`, `Participating in existing transaction`,
and `Suspending current transaction`.

---

## Core API

### Accounts

```bash
# List accounts
curl -s $BASE/accounts | jq

# Open an account
curl -s -X POST $BASE/accounts \
  -H 'Content-Type: application/json' \
  -d '{"owner":"Carol","initialBalance":300}' | jq

# Get one account
curl -s $BASE/accounts/1 | jq
```

### Transfers

The correct implementation: `rollbackFor` on the checked exception, rows locked with
`SELECT ... FOR UPDATE` in a consistent order (lowest id first) to prevent lost updates and deadlocks.

```bash
curl -s -X POST $BASE/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromId":1,"toId":2,"amount":250}' | jq
```

```json
{ "fromId": 1, "fromBalance": 750.00, "toId": 2, "toBalance": 750.00 }
```

Insufficient funds → `422`, rolled back:

```bash
curl -s -X POST $BASE/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromId":2,"toId":1,"amount":99999}' | jq
```

```json
{
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Account #2 has 750.00 but 99999 was requested",
  "instance": "/api/transfers"
}
```

Invalid input (negative amount, same account, missing fields) → `400`.

### Audit log

```bash
curl -s $BASE/audit | jq
```

Every transfer writes an `ATTEMPT` row and, only if it commits, a `SUCCESS` row.
See the [propagation demo](#4-propagation-requires_new-vs-required) for why.

---

## Demos

All demos are `POST`. The response shape is the same for each:

| Field       | Meaning                                                        |
|-------------|----------------------------------------------------------------|
| `before`    | Balances before the scenario ran                               |
| `after`     | Balances after, re-read from the database in a new transaction |
| `exception` | What the caller saw, or `null`                                 |
| `lesson`    | One-line explanation                                           |

Outputs below are trimmed to the interesting fields; account ids will differ on your machine.

### 1. Checked exceptions don't roll back

By default Spring rolls back only on `RuntimeException` and `Error`. `InsufficientFundsException`
is checked, so a transfer that credits the receiver and *then* fails the debit still **commits**.

```bash
curl -s -X POST "$BASE/demos/checked-exception" | jq '{before, after, exception}'
```

```json
{
  "before": { "#3 demo-from": 50.00, "#4 demo-to": 0.00 },
  "after":  { "#3 demo-from": 50.00, "#4 demo-to": 100.00 },
  "exception": "InsufficientFundsException: Account #3 has 50.00 but 100.00 was requested"
}
```

The caller got an exception, *and* 100.00 appeared from nowhere.

**Fix:** `@Transactional(rollbackFor = InsufficientFundsException.class)`

```bash
curl -s -X POST "$BASE/demos/checked-exception?fixed=true" | jq '{before, after, exception}'
```

```json
{
  "before": { "#5 demo-from": 50.00, "#6 demo-to": 0.00 },
  "after":  { "#5 demo-from": 50.00, "#6 demo-to": 0.00 },
  "exception": "InsufficientFundsException: Account #5 has 50.00 but 100.00 was requested"
}
```

Code: `TransferService.transferWithDefaultRollbackRules` vs `TransferService.transfer`.

### 2. Self-invocation bypasses the proxy

`@Transactional` works through a proxy wrapped around the bean. A call via `this` never reaches
the proxy, so no transaction starts. The same applies to `private` methods — the annotation is
silently ignored.

```bash
curl -s -X POST "$BASE/demos/self-invocation" | jq '{before, after, exception}'
```

```json
{
  "before": { "#7 demo-self": 100.00 },
  "after":  { "#7 demo-self": 200.00 },
  "exception": "IllegalStateException: Simulated failure after crediting account #7"
}
```

With no surrounding transaction, `repository.save()` committed on its own, before the exception.

**Fix:** call through the proxy — inject the bean into itself with `@Lazy`, or (usually better)
move the method into a separate bean.

```bash
curl -s -X POST "$BASE/demos/self-invocation?fixed=true" | jq '{before, after, exception}'
```

```json
{
  "before": { "#8 demo-self": 100.00 },
  "after":  { "#8 demo-self": 100.00 },
  "exception": "IllegalStateException: Simulated failure after crediting account #8"
}
```

Code: `SelfInvocationDemo`.

### 3. Swallowing an inner exception → `UnexpectedRollbackException`

The outer method credits an account, calls an inner `@Transactional` method that throws, catches
the exception, and carries on. But the inner call **joined** the outer transaction (`REQUIRED`), and
when the exception crossed its proxy it marked the *shared* transaction rollback-only. Catching it
afterwards is too late: the outer commit fails.

```bash
curl -s -X POST "$BASE/demos/swallowed-exception" | jq '{before, after, exception}'
```

```json
{
  "before": { "#9 demo-swallow": 100.00 },
  "after":  { "#9 demo-swallow": 100.00 },
  "exception": "UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only"
}
```

**Fix:** give the inner step its own transaction with `Propagation.REQUIRES_NEW` (or don't catch —
let the whole unit fail).

```bash
curl -s -X POST "$BASE/demos/swallowed-exception?fixed=true" | jq '{before, after, exception}'
```

```json
{
  "before": { "#10 demo-swallow": 100.00 },
  "after":  { "#10 demo-swallow": 200.00 },
  "exception": null
}
```

Code: `SwallowedExceptionDemo`, `RiskyStep`.

### 4. Propagation: `REQUIRES_NEW` vs `REQUIRED`

A transfer that fails for insufficient funds. `transfer()` writes two audit rows:

- `ATTEMPT` via `REQUIRES_NEW` — suspends the transfer's transaction and commits independently.
- `SUCCESS` via `REQUIRED` — joins the transfer's transaction and rolls back with it.

```bash
curl -s -X POST "$BASE/demos/propagation" | jq '{after, exception}'
```

```json
{
  "after": {
    "#13 demo-prop-from": 10.00,
    "#14 demo-prop-to": 0.00,
    "auditRows": ["ATTEMPT transfer 100.00 from #13 to #14"]
  },
  "exception": "InsufficientFundsException: Account #13 has 10.00 but 100.00 was requested"
}
```

Balances untouched, the attempt is still on record, and there's no `SUCCESS` row.
This is the standard pattern for audit logs that must outlive a failed business operation.

Code: `AuditService.recordIndependently` / `recordInCurrentTransaction`.

### 5. Propagation: `MANDATORY`

`MANDATORY` never starts a transaction — it asserts the caller already has one. Called directly
from a non-transactional context, it throws.

```bash
curl -s -X POST "$BASE/demos/mandatory" | jq '{exception}'
```

```json
{
  "exception": "IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'"
}
```

Useful for methods that must never run as their own unit of work, such as a step that only makes
sense as part of a larger transaction.

Code: `AuditService.recordMandatory`.

### 6. Isolation: lost update under `READ_COMMITTED`

Two concurrent withdrawals of 30.00 from 100.00. Each transaction reads the balance, pauses 300 ms,
then writes. `READ_COMMITTED` prevents dirty reads but **not** lost updates: both read 100.00, both
write 70.00.

```bash
curl -s -X POST "$BASE/demos/lost-update" | jq '{before, after}'
```

```json
{
  "before": { "#11 demo-lost-update": 100.00 },
  "after":  {
    "#11 demo-lost-update": 70.00,
    "expected": 40.00,
    "withdrawals": ["ok", "ok"]
  }
}
```

Note `"withdrawals": ["ok", "ok"]` — both callers were told they succeeded. That's what makes a lost
update so dangerous: nothing fails, the money is just wrong.

**Fix:** lock the row on read with `@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`). The second
withdrawal blocks until the first commits, then reads 70.00.

```bash
curl -s -X POST "$BASE/demos/lost-update?fixed=true" | jq '{before, after}'
```

```json
{
  "before": { "#12 demo-lost-update": 100.00 },
  "after":  {
    "#12 demo-lost-update": 40.00,
    "expected": 40.00,
    "withdrawals": ["ok", "ok"]
  }
}
```

Code: `WithdrawalService.withdrawUnsafe` vs `withdrawLocked`.

### 7. Isolation: optimistic locking with `@Version`

The same race as demo 6, with no row lock — but on a `VersionedAccount` entity that has a
`@Version` column. Hibernate turns every update into:

```sql
update versioned_account set balance=?, owner=?, version=? where id=? and version=?
```

If another transaction committed first, the `where ... version=?` matches zero rows and the commit
fails. The stale write is **rejected**, not silently applied.

```bash
curl -s -X POST "$BASE/demos/optimistic-lock" | jq '{before, after}'
```

```json
{
  "before": { "#1 demo-optimistic": 100.00 },
  "after":  {
    "#1 demo-optimistic": 70.00,
    "expected": 40.00,
    "version": 1,
    "withdrawals": ["ok", "ObjectOptimisticLockingFailureException"]
  }
}
```

The balance is still 70.00, but unlike demo 6 the losing caller *knows* its withdrawal didn't happen.

**Fix:** retry on `OptimisticLockingFailureException`. The retry must run **outside** the failed
transaction — the conflict only surfaces at commit, and retrying inside it would re-use a stale
persistence context. `RetryingWithdrawal` is a separate, non-transactional bean, so each attempt goes
through the proxy and gets a fresh transaction that re-reads the current balance and version.

```bash
curl -s -X POST "$BASE/demos/optimistic-lock?fixed=true" | jq '{before, after}'
```

```json
{
  "before": { "#2 demo-optimistic": 100.00 },
  "after":  {
    "#2 demo-optimistic": 40.00,
    "expected": 40.00,
    "version": 2,
    "withdrawals": ["ok after 1 attempt(s)", "ok after 2 attempt(s)"]
  }
}
```

**Pessimistic (demo 6) vs optimistic (demo 7):**

|                    | Pessimistic `FOR UPDATE`                | Optimistic `@Version`                         |
|--------------------|-----------------------------------------|-----------------------------------------------|
| On conflict        | Second writer **waits**                 | Second writer **fails**, must retry           |
| Holds DB locks     | Yes, until commit                       | No                                            |
| Deadlock risk      | Yes — lock in a consistent order        | No                                            |
| Best when          | Conflicts are frequent (hot rows)       | Conflicts are rare; long think-time / user edits |

`VersionedAccount` is a separate entity so demo 6 can still show the unprotected behaviour; in a real
app you would put `@Version` on `Account` itself.

Code: `OptimisticWithdrawalService`, `RetryingWithdrawal`, `VersionedAccount`.

---

## Deadlock prevention

Not a demo endpoint, but covered by `oppositeConcurrentTransfersDoNotDeadlock`: 50 A→B and 50 B→A
transfers run concurrently. `TransferService.lockBoth` always locks the lower account id first, so
opposite transfers queue on the same row instead of each holding one lock and waiting on the other.
With argument-order locking, H2 reports `Deadlock detected` and the test fails.

## Project layout

```
src/main/java/com/example/transactions/
├── domain/        Account, VersionedAccount, AuditLog, exceptions
├── repository/    Spring Data repositories (incl. the FOR UPDATE query)
├── service/       AccountService, TransferService, AuditService — the correct patterns
├── demo/          One bean per pitfall, plus DemoRunner that orchestrates them
└── web/           REST controllers and ProblemDetail error handling
```

`spring.jpa.open-in-view` is disabled on purpose, so transaction boundaries in the demos are exactly
what the annotations say, not stretched across the whole HTTP request.

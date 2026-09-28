package com.example.transactions.demo;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.VersionedAccount;
import com.example.transactions.service.AccountService;
import com.example.transactions.service.AuditService;
import com.example.transactions.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/**
 * Runs each scenario against freshly opened accounts and reports balances before/after.
 * Deliberately NOT {@code @Transactional}: each call below must start its own transaction.
 */
@Service
public class DemoRunner {

    public record DemoResult(
            String scenario,
            String variant,
            Map<String, BigDecimal> before,
            Map<String, Object> after,
            String exception,
            String lesson) {
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final AccountService accounts;
    private final AuditService audit;
    private final TransferService transfers;
    private final SelfInvocationDemo selfInvocation;
    private final SwallowedExceptionDemo swallowed;
    private final WithdrawalService withdrawals;
    private final OptimisticWithdrawalService optimistic;
    private final RetryingWithdrawal retrying;

    public DemoRunner(AccountService accounts, AuditService audit, TransferService transfers,
                      SelfInvocationDemo selfInvocation, SwallowedExceptionDemo swallowed,
                      WithdrawalService withdrawals, OptimisticWithdrawalService optimistic,
                      RetryingWithdrawal retrying) {
        this.accounts = accounts;
        this.audit = audit;
        this.transfers = transfers;
        this.selfInvocation = selfInvocation;
        this.swallowed = swallowed;
        this.withdrawals = withdrawals;
        this.optimistic = optimistic;
        this.retrying = retrying;
    }

    /** Rollback rules: checked exception with and without {@code rollbackFor}. */
    public DemoResult checkedException(boolean withRollbackFor) {
        Account from = accounts.open("demo-from", new BigDecimal("50.00"));
        Account to = accounts.open("demo-to", new BigDecimal("0.00"));
        Map<String, BigDecimal> before = balances(from, to);

        String error = capture(() -> {
            if (withRollbackFor) {
                transfers.transfer(from.getId(), to.getId(), HUNDRED);
            } else {
                transfers.transferWithDefaultRollbackRules(from.getId(), to.getId(), HUNDRED);
            }
        });

        return new DemoResult("checked-exception",
                withRollbackFor ? "rollbackFor = InsufficientFundsException.class" : "default rollback rules",
                before, new LinkedHashMap<>(balances(from, to)), error,
                withRollbackFor
                        ? "Rolled back: the receiver was not credited."
                        : "Committed despite the exception: the receiver got 100.00 that was never debited.");
    }

    /** Self-invocation: calling a @Transactional method via {@code this} vs. via the proxy. */
    public DemoResult selfInvocation(boolean throughProxy) {
        Account account = accounts.open("demo-self", HUNDRED);
        Map<String, BigDecimal> before = balances(account);

        String error = capture(() -> selfInvocation.creditThenFail(account.getId(), HUNDRED, throughProxy));

        return new DemoResult("self-invocation", throughProxy ? "call through proxy" : "this.method()",
                before, new LinkedHashMap<>(balances(account)), error,
                throughProxy
                        ? "Proxy started a transaction; the RuntimeException rolled the credit back."
                        : "No proxy, no transaction: save() committed on its own and the credit stuck.");
    }

    /** Catching an exception thrown by an inner @Transactional call. */
    public DemoResult swallowedException(boolean innerRequiresNew) {
        Account account = accounts.open("demo-swallow", HUNDRED);
        Map<String, BigDecimal> before = balances(account);

        String error = capture(() -> swallowed.creditThenSwallowInnerFailure(account.getId(), HUNDRED, innerRequiresNew));

        return new DemoResult("swallowed-exception", innerRequiresNew ? "inner REQUIRES_NEW" : "inner REQUIRED",
                before, new LinkedHashMap<>(balances(account)), error,
                innerRequiresNew
                        ? "Inner failure stayed in its own transaction; outer credit committed."
                        : "Inner proxy marked the shared transaction rollback-only; outer commit threw UnexpectedRollbackException.");
    }

    /** Propagation: REQUIRES_NEW audit survives the rollback, REQUIRED audit does not. */
    public DemoResult propagation() {
        Account from = accounts.open("demo-prop-from", new BigDecimal("10.00"));
        Account to = accounts.open("demo-prop-to", new BigDecimal("0.00"));
        Map<String, BigDecimal> before = balances(from, to);

        String error = capture(() -> transfers.transfer(from.getId(), to.getId(), HUNDRED));

        String marker = "from #%d to #%d".formatted(from.getId(), to.getId());
        Map<String, Object> after = new LinkedHashMap<>(balances(from, to));
        after.put("auditRows", audit.containing(marker).stream().map(log -> log.getMessage()).toList());

        return new DemoResult("propagation", "REQUIRES_NEW vs REQUIRED", before, after, error,
                "Only the ATTEMPT row (REQUIRES_NEW) survived; the SUCCESS row (REQUIRED) never existed.");
    }

    /** Propagation.MANDATORY called with no active transaction. */
    public DemoResult mandatory() {
        String error = capture(() -> audit.recordMandatory("mandatory-demo-" + UUID.randomUUID()));
        return new DemoResult("mandatory", "no surrounding transaction", Map.of(), Map.of(), error,
                "MANDATORY refuses to start a transaction: it asserts the caller already opened one.");
    }

    /** Isolation: two concurrent withdrawals of 30 from 100, with and without a row lock. */
    public DemoResult lostUpdate(boolean locked) {
        Account account = accounts.open("demo-lost-update", HUNDRED);
        Map<String, BigDecimal> before = balances(account);
        BigDecimal amount = new BigDecimal("30.00");
        long thinkMillis = 300;

        List<String> outcomes = runTwiceConcurrently(() -> {
            if (locked) {
                withdrawals.withdrawLocked(account.getId(), amount, thinkMillis);
            } else {
                withdrawals.withdrawUnsafe(account.getId(), amount, thinkMillis);
            }
            return "ok";
        });

        Map<String, Object> after = new LinkedHashMap<>(balances(account));
        after.put("expected", HUNDRED.subtract(amount).subtract(amount));
        after.put("withdrawals", outcomes);
        return new DemoResult("lost-update", locked ? "PESSIMISTIC_WRITE lock" : "READ_COMMITTED, no lock",
                before, after, null,
                locked
                        ? "Second withdrawal waited for the first to commit, then read 70.00 → 40.00."
                        : "Both read 100.00 and wrote 70.00: one withdrawal of 30.00 was lost.");
    }

    /** Isolation, optimistic: the same race as {@link #lostUpdate}, but {@code @Version} catches it at commit. */
    public DemoResult optimisticLock(boolean withRetry) {
        VersionedAccount account = optimistic.open("demo-optimistic", HUNDRED);
        Map<String, BigDecimal> before = Map.of(label(account), account.getBalance());
        BigDecimal amount = new BigDecimal("30.00");
        long thinkMillis = 300;

        List<String> outcomes = runTwiceConcurrently(() -> {
            if (withRetry) {
                int attempts = retrying.withdraw(account.getId(), amount, thinkMillis, 3);
                return "ok after %d attempt(s)".formatted(attempts);
            }
            optimistic.withdraw(account.getId(), amount, thinkMillis);
            return "ok";
        });

        VersionedAccount current = optimistic.get(account.getId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(label(current), current.getBalance());
        after.put("expected", HUNDRED.subtract(amount).subtract(amount));
        after.put("version", current.getVersion());
        after.put("withdrawals", outcomes);
        return new DemoResult("optimistic-lock", withRetry ? "@Version + retry" : "@Version, no retry",
                before, after, null,
                withRetry
                        ? "The loser's commit failed on the version check, retried in a fresh transaction, and read 70.00 → 40.00."
                        : "No update was silently lost: the loser's commit failed on the version check and it was told so.");
    }

    /**
     * Runs the task on two threads at once; each result is the task's return value or the exception it threw.
     * Results come back in completion order, so the demo's winner is listed first.
     */
    private static List<String> runTwiceConcurrently(Callable<String> task) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletionService<String> completion = new ExecutorCompletionService<>(pool);
            for (int i = 0; i < 2; i++) {
                completion.submit(() -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e.getClass().getSimpleName();
                    }
                });
            }
            List<String> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(completion.take().get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    private static String label(VersionedAccount a) {
        return "#%d %s".formatted(a.getId(), a.getOwner());
    }

    private Map<String, BigDecimal> balances(Account... snapshot) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Account a : snapshot) {
            result.put("#%d %s".formatted(a.getId(), a.getOwner()), accounts.get(a.getId()).getBalance());
        }
        return result;
    }

    private static String capture(Action action) {
        try {
            action.run();
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}

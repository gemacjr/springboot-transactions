package com.example.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import com.example.transactions.demo.DemoRunner;
import com.example.transactions.demo.DemoRunner.DemoResult;
import com.example.transactions.domain.Account;
import com.example.transactions.domain.InsufficientFundsException;
import com.example.transactions.service.AccountService;
import com.example.transactions.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TransactionPitfallsTest {

    @Autowired
    DemoRunner demos;

    @Autowired
    AccountService accounts;

    @Autowired
    TransferService transfers;

    private static List<BigDecimal> balances(DemoResult result) {
        return result.after().entrySet().stream()
                .filter(e -> e.getKey().startsWith("#"))
                .map(e -> (BigDecimal) e.getValue())
                .toList();
    }

    @Test
    void successfulTransferMovesMoney() throws InsufficientFundsException {
        Account from = accounts.open("a", new BigDecimal("200.00"));
        Account to = accounts.open("b", new BigDecimal("0.00"));

        transfers.transfer(from.getId(), to.getId(), new BigDecimal("75.00"));

        assertThat(accounts.get(from.getId()).getBalance()).isEqualByComparingTo("125.00");
        assertThat(accounts.get(to.getId()).getBalance()).isEqualByComparingTo("75.00");
    }

    @Test
    void checkedExceptionCommitsByDefault() {
        DemoResult result = demos.checkedException(false);

        assertThat(result.exception()).startsWith("InsufficientFundsException");
        assertThat(balances(result)).containsExactly(new BigDecimal("50.00"), new BigDecimal("100.00"));
    }

    @Test
    void checkedExceptionRollsBackWithRollbackFor() {
        DemoResult result = demos.checkedException(true);

        assertThat(result.exception()).startsWith("InsufficientFundsException");
        assertThat(balances(result)).containsExactly(new BigDecimal("50.00"), new BigDecimal("0.00"));
    }

    @Test
    void selfInvocationBypassesTransaction() {
        assertThat(balances(demos.selfInvocation(false))).containsExactly(new BigDecimal("200.00"));
    }

    @Test
    void callThroughProxyRollsBack() {
        assertThat(balances(demos.selfInvocation(true))).containsExactly(new BigDecimal("100.00"));
    }

    @Test
    void swallowingJoinedFailureCausesUnexpectedRollback() {
        DemoResult result = demos.swallowedException(false);

        assertThat(result.exception()).startsWith("UnexpectedRollbackException");
        assertThat(balances(result)).containsExactly(new BigDecimal("100.00"));
    }

    @Test
    void requiresNewIsolatesInnerFailure() {
        DemoResult result = demos.swallowedException(true);

        assertThat(result.exception()).isNull();
        assertThat(balances(result)).containsExactly(new BigDecimal("200.00"));
    }

    @Test
    void requiresNewAuditSurvivesRollbackButRequiredDoesNot() {
        DemoResult result = demos.propagation();

        assertThat(result.exception()).startsWith("InsufficientFundsException");
        assertThat((List<?>) result.after().get("auditRows"))
                .singleElement().asString().startsWith("ATTEMPT");
    }

    @Test
    void mandatoryRequiresExistingTransaction() {
        assertThat(demos.mandatory().exception()).startsWith("IllegalTransactionStateException");
    }

    @Test
    void readCommittedLosesConcurrentUpdate() {
        assertThat(balances(demos.lostUpdate(false))).containsExactly(new BigDecimal("70.00"));
    }

    @Test
    void pessimisticLockPreventsLostUpdate() {
        assertThat(balances(demos.lostUpdate(true))).containsExactly(new BigDecimal("40.00"));
    }

    @Test
    void optimisticLockRejectsStaleWriteInsteadOfLosingIt() {
        DemoResult result = demos.optimisticLock(false);

        assertThat(balances(result)).containsExactly(new BigDecimal("70.00"));
        assertThat(result.after().get("version")).isEqualTo(1L);
        assertThat(result.after().get("withdrawals")).asInstanceOf(LIST)
                .containsExactly("ok", "ObjectOptimisticLockingFailureException");
    }

    @Test
    void optimisticLockWithRetryAppliesBothWithdrawals() {
        DemoResult result = demos.optimisticLock(true);

        assertThat(balances(result)).containsExactly(new BigDecimal("40.00"));
        assertThat(result.after().get("version")).isEqualTo(2L);
        assertThat(result.after().get("withdrawals")).asInstanceOf(LIST)
                .containsExactly("ok after 1 attempt(s)", "ok after 2 attempt(s)");
    }

    @Test
    void oppositeConcurrentTransfersDoNotDeadlock() throws Exception {
        Account a = accounts.open("deadlock-a", new BigDecimal("1000.00"));
        Account b = accounts.open("deadlock-b", new BigDecimal("1000.00"));
        int rounds = 50;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                futures.add(pool.submit(() -> transfers.transfer(a.getId(), b.getId(), BigDecimal.ONE)));
                futures.add(pool.submit(() -> transfers.transfer(b.getId(), a.getId(), BigDecimal.ONE)));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS); // rethrows any lock timeout / deadlock
            }
        } finally {
            pool.shutdown();
        }

        assertThat(accounts.get(a.getId()).getBalance()).isEqualByComparingTo("1000.00");
        assertThat(accounts.get(b.getId()).getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void rejectsSelfTransfer() {
        Account a = accounts.open("self", BigDecimal.TEN);
        assertThatThrownBy(() -> transfers.transfer(a.getId(), a.getId(), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

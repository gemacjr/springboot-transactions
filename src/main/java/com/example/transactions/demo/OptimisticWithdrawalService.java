package com.example.transactions.demo;

import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.domain.InsufficientFundsException;
import com.example.transactions.domain.VersionedAccount;
import com.example.transactions.repository.VersionedAccountRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolation demo, optimistic variant: no row lock, same READ_COMMITTED race as
 * {@link WithdrawalService#withdrawUnsafe} — but {@code @Version} detects the conflict at commit.
 */
@Service
public class OptimisticWithdrawalService {

    private final VersionedAccountRepository accounts;

    public OptimisticWithdrawalService(VersionedAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public VersionedAccount open(String owner, BigDecimal balance) {
        return accounts.save(new VersionedAccount(owner, balance));
    }

    @Transactional(readOnly = true)
    public VersionedAccount get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Plain read, pause, write. The loser of the race gets
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} when its commit flushes the UPDATE.
     */
    @Transactional(rollbackFor = InsufficientFundsException.class)
    public void withdraw(Long accountId, BigDecimal amount, long thinkMillis) throws InsufficientFundsException {
        VersionedAccount account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        pause(thinkMillis);
        account.debit(amount);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

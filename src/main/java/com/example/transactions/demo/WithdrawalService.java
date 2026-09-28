package com.example.transactions.demo;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.domain.InsufficientFundsException;
import com.example.transactions.repository.AccountRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolation demo: the read-modify-write "lost update".
 * READ_COMMITTED (the default for most databases) prevents dirty reads, but NOT lost updates:
 * two transactions can both read balance=100, both write 100-30=70, and one withdrawal vanishes.
 */
@Service
public class WithdrawalService {

    private final AccountRepository accounts;

    public WithdrawalService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** BUG: plain read, then write. The pause widens the race window so it reproduces reliably. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = InsufficientFundsException.class)
    public void withdrawUnsafe(Long accountId, BigDecimal amount, long thinkMillis) throws InsufficientFundsException {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        pause(thinkMillis);
        account.debit(amount);
    }

    /** FIX: SELECT ... FOR UPDATE — the second withdrawal blocks until the first commits, then reads the new balance. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = InsufficientFundsException.class)
    public void withdrawLocked(Long accountId, BigDecimal amount, long thinkMillis) throws InsufficientFundsException {
        Account account = accounts.findByIdForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
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

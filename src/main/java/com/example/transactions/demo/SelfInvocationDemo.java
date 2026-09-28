package com.example.transactions.demo;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.repository.AccountRepository;
import java.math.BigDecimal;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BUG: self-invocation. {@code @Transactional} is implemented by a proxy that wraps this bean;
 * a call through {@code this} never goes through the proxy, so the annotation is silently ignored.
 * The same applies to {@code private} methods — the proxy can't override them, so {@code @Transactional}
 * on a private method never does anything.
 */
@Service
public class SelfInvocationDemo {

    private final AccountRepository accounts;
    private final SelfInvocationDemo self;

    /** {@code @Lazy} injects a proxy to ourselves without a circular-reference failure at startup. */
    public SelfInvocationDemo(AccountRepository accounts, @Lazy SelfInvocationDemo self) {
        this.accounts = accounts;
        this.self = self;
    }

    public void creditThenFail(Long accountId, BigDecimal amount, boolean throughProxy) {
        if (throughProxy) {
            self.creditThenFailTransactionally(accountId, amount); // FIX: goes through the proxy
        } else {
            creditThenFailTransactionally(accountId, amount);      // BUG: this.call → no transaction
        }
    }

    @Transactional
    public void creditThenFailTransactionally(Long accountId, BigDecimal amount) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        account.credit(amount);
        // Without a surrounding transaction, save() runs in its own repository transaction and commits immediately.
        accounts.save(account);
        throw new IllegalStateException("Simulated failure after crediting account #" + accountId);
    }
}

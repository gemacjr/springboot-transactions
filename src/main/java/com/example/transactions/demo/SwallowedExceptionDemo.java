package com.example.transactions.demo;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.repository.AccountRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BUG: catching an exception from an inner {@code @Transactional} call does not "save" the outer
 * transaction. The inner proxy already marked it rollback-only, so the outer commit fails with
 * {@link org.springframework.transaction.UnexpectedRollbackException} and the credit is lost.
 */
@Service
public class SwallowedExceptionDemo {

    private static final Logger log = LoggerFactory.getLogger(SwallowedExceptionDemo.class);

    private final AccountRepository accounts;
    private final RiskyStep riskyStep;

    public SwallowedExceptionDemo(AccountRepository accounts, RiskyStep riskyStep) {
        this.accounts = accounts;
        this.riskyStep = riskyStep;
    }

    @Transactional
    public void creditThenSwallowInnerFailure(Long accountId, BigDecimal amount, boolean innerRequiresNew) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        account.credit(amount);
        try {
            if (innerRequiresNew) {
                riskyStep.failInOwnTransaction();          // FIX: failure isolated to the inner transaction
            } else {
                riskyStep.failJoiningCallerTransaction(); // BUG: poisons the shared transaction
            }
        } catch (IllegalStateException e) {
            log.warn("Swallowed inner failure, carrying on: {}", e.getMessage());
        }
    }
}

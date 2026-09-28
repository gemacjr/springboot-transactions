package com.example.transactions.domain;

import java.math.BigDecimal;

/** Checked on purpose — see {@link Account#debit(BigDecimal)}. */
public class InsufficientFundsException extends Exception {

    public InsufficientFundsException(Long accountId, BigDecimal balance, BigDecimal requested) {
        super("Account #%d has %s but %s was requested".formatted(accountId, balance, requested));
    }
}

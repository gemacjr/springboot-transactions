package com.example.transactions.domain;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("Account #%d not found".formatted(id));
    }
}

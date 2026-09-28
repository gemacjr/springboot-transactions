package com.example.transactions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;

@Entity
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    protected Account() {
    }

    public Account(String owner, BigDecimal balance) {
        this.owner = owner;
        this.balance = balance;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    /**
     * Throws a <em>checked</em> exception on purpose: by default Spring does NOT roll back
     * for checked exceptions, which is one of the classic transaction bugs.
     */
    public void debit(BigDecimal amount) throws InsufficientFundsException {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        balance = balance.subtract(amount);
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}

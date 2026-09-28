package com.example.transactions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/**
 * Same shape as {@link Account} plus {@code @Version}. Kept separate so the lost-update demo on
 * {@link Account} still shows the unprotected behaviour.
 *
 * <p>Hibernate turns every update into {@code UPDATE ... SET version = v+1 WHERE id = ? AND version = v}.
 * If another transaction committed first, zero rows match and the commit fails instead of overwriting.
 */
@Entity
public class VersionedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private Long version;

    protected VersionedAccount() {
    }

    public VersionedAccount(String owner, BigDecimal balance) {
        this.owner = owner;
        this.balance = balance;
    }

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

    public Long getVersion() {
        return version;
    }
}

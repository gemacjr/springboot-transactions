package com.example.transactions.service;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true) // class-level default; write methods override it
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account open(String owner, BigDecimal initialBalance) {
        return accounts.save(new Account(owner, initialBalance));
    }

    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public List<Account> list() {
        return accounts.findAll();
    }
}

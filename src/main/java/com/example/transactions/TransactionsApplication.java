package com.example.transactions;

import com.example.transactions.service.AccountService;
import java.math.BigDecimal;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TransactionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionsApplication.class, args);
    }

    @Bean
    ApplicationRunner seedAccounts(AccountService accounts) {
        return args -> {
            accounts.open("Alice", new BigDecimal("1000.00"));
            accounts.open("Bob", new BigDecimal("500.00"));
        };
    }
}

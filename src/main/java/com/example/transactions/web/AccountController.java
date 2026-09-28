package com.example.transactions.web;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AuditLog;
import com.example.transactions.domain.InsufficientFundsException;
import com.example.transactions.service.AccountService;
import com.example.transactions.service.AuditService;
import com.example.transactions.service.TransferService;
import com.example.transactions.service.TransferService.TransferResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountController {

    public record OpenAccountRequest(@NotBlank String owner, @NotNull @DecimalMin("0.00") BigDecimal initialBalance) {
    }

    public record TransferRequest(@NotNull Long fromId, @NotNull Long toId,
                                  @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount) {
    }

    private final AccountService accounts;
    private final TransferService transfers;
    private final AuditService audit;

    public AccountController(AccountService accounts, TransferService transfers, AuditService audit) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.audit = audit;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Account open(@Valid @RequestBody OpenAccountRequest request) {
        return accounts.open(request.owner(), request.initialBalance());
    }

    @GetMapping("/accounts")
    public List<Account> list() {
        return accounts.list();
    }

    @GetMapping("/accounts/{id}")
    public Account get(@PathVariable Long id) {
        return accounts.get(id);
    }

    @PostMapping("/transfers")
    public TransferResult transfer(@Valid @RequestBody TransferRequest request) throws InsufficientFundsException {
        return transfers.transfer(request.fromId(), request.toId(), request.amount());
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() {
        return audit.all();
    }
}

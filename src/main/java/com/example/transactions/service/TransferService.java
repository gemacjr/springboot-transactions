package com.example.transactions.service;

import com.example.transactions.domain.Account;
import com.example.transactions.domain.AccountNotFoundException;
import com.example.transactions.domain.InsufficientFundsException;
import com.example.transactions.repository.AccountRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    public record TransferResult(Long fromId, BigDecimal fromBalance, Long toId, BigDecimal toBalance) {
    }

    private record AccountPair(Account from, Account to) {
    }

    private final AccountRepository accounts;
    private final AuditService audit;

    public TransferService(AccountRepository accounts, AuditService audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * The correct version.
     * <ul>
     *   <li>{@code rollbackFor} — InsufficientFundsException is checked, so without this the
     *       credit below would be committed even though the debit failed.</li>
     *   <li>The ATTEMPT audit uses REQUIRES_NEW, so it survives a rollback; the SUCCESS audit uses
     *       REQUIRED, so it only exists if the transfer commits.</li>
     *   <li>Both rows are locked (SELECT ... FOR UPDATE) so concurrent transfers can't lose updates.</li>
     * </ul>
     */
    @Transactional(rollbackFor = InsufficientFundsException.class)
    public TransferResult transfer(Long fromId, Long toId, BigDecimal amount) throws InsufficientFundsException {
        validate(fromId, toId, amount);
        audit.recordIndependently("ATTEMPT transfer %s from #%d to #%d".formatted(amount, fromId, toId));

        AccountPair pair = lockBoth(fromId, toId);
        // Credit first on purpose: it makes a missing rollback visible as money appearing from nowhere.
        pair.to().credit(amount);
        pair.from().debit(amount);

        audit.recordInCurrentTransaction("SUCCESS transfer %s from #%d to #%d".formatted(amount, fromId, toId));
        return new TransferResult(fromId, pair.from().getBalance(), toId, pair.to().getBalance());
    }

    /**
     * BUG: default rollback rules only roll back on RuntimeException and Error. The checked
     * InsufficientFundsException propagates to the caller, but the transaction still COMMITS the credit.
     */
    @Transactional
    public void transferWithDefaultRollbackRules(Long fromId, Long toId, BigDecimal amount)
            throws InsufficientFundsException {
        validate(fromId, toId, amount);
        AccountPair pair = lockBoth(fromId, toId);
        pair.to().credit(amount);
        pair.from().debit(amount);
    }

    /**
     * Locks both accounts with SELECT ... FOR UPDATE for the rest of the transaction.
     * Always locks the lower id first, so A→B and a concurrent B→A queue on the same row
     * instead of each holding one lock and waiting on the other (deadlock).
     */
    private AccountPair lockBoth(Long fromId, Long toId) {
        boolean fromFirst = fromId < toId;
        Account first = lock(fromFirst ? fromId : toId);
        Account second = lock(fromFirst ? toId : fromId);
        return fromFirst ? new AccountPair(first, second) : new AccountPair(second, first);
    }

    private Account lock(Long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private static void validate(Long fromId, Long toId, BigDecimal amount) {
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}

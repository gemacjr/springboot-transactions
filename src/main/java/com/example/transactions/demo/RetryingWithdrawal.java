package com.example.transactions.demo;

import com.example.transactions.domain.InsufficientFundsException;
import java.math.BigDecimal;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Retries an optimistic conflict. Deliberately NOT {@code @Transactional} and a separate bean:
 * each attempt must go through {@link OptimisticWithdrawalService}'s proxy to get a fresh
 * transaction and re-read the current balance and version. Retrying inside the failed
 * transaction would re-use its stale persistence context and fail forever.
 */
@Service
public class RetryingWithdrawal {

    private final OptimisticWithdrawalService withdrawals;

    public RetryingWithdrawal(OptimisticWithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    /** @return the number of attempts it took */
    public int withdraw(Long accountId, BigDecimal amount, long thinkMillis, int maxAttempts)
            throws InsufficientFundsException {
        for (int attempt = 1; ; attempt++) {
            try {
                withdrawals.withdraw(accountId, amount, thinkMillis);
                return attempt;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
            }
        }
    }
}

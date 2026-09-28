package com.example.transactions.service;

import com.example.transactions.domain.AuditLog;
import com.example.transactions.repository.AuditLogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same insert, three propagation behaviours. Must be a separate bean from its callers:
 * propagation is applied by the proxy, so it only takes effect on calls coming from outside.
 */
@Service
public class AuditService {

    private final AuditLogRepository logs;

    public AuditService(AuditLogRepository logs) {
        this.logs = logs;
    }

    /** REQUIRED (default): joins the caller's transaction, so it rolls back with it. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordInCurrentTransaction(String message) {
        logs.save(new AuditLog(message));
    }

    /** REQUIRES_NEW: suspends the caller's transaction and commits on its own — survives a caller rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String message) {
        logs.save(new AuditLog(message));
    }

    /** MANDATORY: throws IllegalTransactionStateException if no transaction is already active. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordMandatory(String message) {
        logs.save(new AuditLog(message));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> all() {
        return logs.findAll();
    }

    @Transactional(readOnly = true)
    public List<AuditLog> containing(String fragment) {
        return logs.findByMessageContaining(fragment);
    }
}

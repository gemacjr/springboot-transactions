package com.example.transactions.demo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** An inner step that always fails — the only difference between the two methods is propagation. */
@Service
public class RiskyStep {

    /**
     * Joins the caller's transaction. When the exception crosses this proxy it marks the
     * SHARED transaction rollback-only — even if the caller catches the exception afterwards.
     */
    @Transactional
    public void failJoiningCallerTransaction() {
        throw new IllegalStateException("Inner step failed (joined outer transaction)");
    }

    /** Runs in its own transaction; its rollback does not touch the caller's transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failInOwnTransaction() {
        throw new IllegalStateException("Inner step failed (own transaction)");
    }
}

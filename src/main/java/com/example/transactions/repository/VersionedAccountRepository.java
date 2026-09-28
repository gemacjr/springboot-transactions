package com.example.transactions.repository;

import com.example.transactions.domain.VersionedAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionedAccountRepository extends JpaRepository<VersionedAccount, Long> {
}

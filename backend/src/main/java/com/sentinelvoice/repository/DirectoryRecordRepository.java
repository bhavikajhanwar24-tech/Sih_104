package com.sentinelvoice.repository;

import com.sentinelvoice.identity.model.DirectoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectoryRecordRepository extends JpaRepository<DirectoryRecord, String> {

    Optional<DirectoryRecord> findByPrimaryCli(String primaryCli);

    Optional<DirectoryRecord> findByExtension(String extension);

    List<DirectoryRecord> findByNameIgnoreCase(String name);

    List<DirectoryRecord> findByRoleIgnoreCase(String role);
}

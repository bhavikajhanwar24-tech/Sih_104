package com.sentinelvoice.repository;

import com.sentinelvoice.model.CallerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CallerProfileRepository extends JpaRepository<CallerProfile, Long> {
    Optional<CallerProfile> findByCallerId(String callerId);
}

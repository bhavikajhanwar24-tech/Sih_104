package com.sentinelvoice.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface LoginFailureRepository extends JpaRepository<LoginFailureEntity, UUID> {

    @Query("""
            select count(f) from LoginFailureEntity f
            where f.tenantId = :tenantId
              and f.emailLower = :email
              and f.ipAddress = :ip
              and f.failedAt >= :since
            """)
    long countRecent(
            @Param("tenantId") UUID tenantId,
            @Param("email") String email,
            @Param("ip") String ip,
            @Param("since") Instant since
    );
}

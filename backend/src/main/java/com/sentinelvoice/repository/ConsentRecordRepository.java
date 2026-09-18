package com.sentinelvoice.repository;

import com.sentinelvoice.passport.model.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    @Query("""
            select c from ConsentRecord c
            where c.employeeId = :employeeId
              and c.purpose = :purpose
              and c.withdrawnAt is null
            order by c.grantedAt desc
            """)
    List<ConsentRecord> findActive(
            @Param("employeeId") String employeeId,
            @Param("purpose") String purpose
    );

    default Optional<ConsentRecord> findActiveConsent(String employeeId, String purpose) {
        List<ConsentRecord> list = findActive(employeeId, purpose);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }
}

package com.sentinelvoice.directory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reverts expired temporary employee statuses (ON_LEAVE / TRAVELLING) via SECURITY DEFINER fn.
 */
@Component
public class EmployeeStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmployeeStatusScheduler.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Scheduled(fixedDelayString = "${sentinelvoice.directory.status-revert-ms:60000}")
    @Transactional
    public void revertExpiredStatuses() {
        Number n = (Number) entityManager
                .createNativeQuery("SELECT fn_revert_expired_employee_status()")
                .getSingleResult();
        if (n != null && n.intValue() > 0) {
            log.info("Reverted {} expired employee status row(s)", n.intValue());
        }
    }
}

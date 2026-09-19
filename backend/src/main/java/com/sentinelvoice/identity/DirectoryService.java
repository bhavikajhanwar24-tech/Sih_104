package com.sentinelvoice.identity;

import com.sentinelvoice.identity.model.DirectoryRecord;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Directory lookup — persistence re-implemented in F4.
 */
@Service
public class DirectoryService {

    private static final String MSG = "re-implemented in F4";

    public Optional<DirectoryRecord> findByEmployeeId(String employeeId) {
        throw new UnsupportedOperationException(MSG);
    }

    public Optional<DirectoryRecord> findByCli(String cli) {
        throw new UnsupportedOperationException(MSG);
    }

    public Optional<DirectoryRecord> findByClaimedIdentity(String claimedName, String claimedRole) {
        throw new UnsupportedOperationException(MSG);
    }

    public long count() {
        throw new UnsupportedOperationException(MSG);
    }

    public static boolean rolesMatch(String claimedRole, String directoryRole) {
        if (claimedRole == null || directoryRole == null) {
            return false;
        }
        String claimed = claimedRole.trim().toLowerCase(Locale.ROOT);
        String directory = directoryRole.trim().toLowerCase(Locale.ROOT);
        return claimed.equals(directory) || directory.contains(claimed) || claimed.contains(directory);
    }
}

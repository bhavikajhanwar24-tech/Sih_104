package com.sentinelvoice.identity;

import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.repository.DirectoryRecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Context §12 stage [2] — CLI / claim → directory record.
 */
@Service
public class DirectoryService {

    private final DirectoryRecordRepository repository;

    public DirectoryService(DirectoryRecordRepository repository) {
        this.repository = repository;
    }

    public Optional<DirectoryRecord> findByEmployeeId(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(employeeId.trim());
    }

    public Optional<DirectoryRecord> findByCli(String cli) {
        if (cli == null || cli.isBlank()) {
            return Optional.empty();
        }
        Optional<DirectoryRecord> byPrimary = repository.findByPrimaryCli(cli.trim());
        if (byPrimary.isPresent()) {
            return byPrimary;
        }
        String digits = TrunkClassifier.normaliseCli(cli);
        return repository.findAll().stream()
                .filter(r -> TrunkClassifier.normaliseCli(r.getPrimaryCli()).equals(digits)
                        || (r.getExtension() != null && r.getExtension().equals(cli.trim())))
                .findFirst();
    }

    public Optional<DirectoryRecord> findByClaimedIdentity(String claimedName, String claimedRole) {
        if (claimedName != null && !claimedName.isBlank()) {
            List<DirectoryRecord> byName = repository.findByNameIgnoreCase(claimedName.trim());
            if (!byName.isEmpty()) {
                if (claimedRole == null || claimedRole.isBlank()) {
                    return Optional.of(byName.getFirst());
                }
                return byName.stream()
                        .filter(r -> rolesMatch(claimedRole, r.getRole()))
                        .findFirst()
                        .or(() -> Optional.of(byName.getFirst()));
            }
        }
        if (claimedRole != null && !claimedRole.isBlank()) {
            return repository.findAll().stream()
                    .filter(r -> rolesMatch(claimedRole, r.getRole()))
                    .findFirst();
        }
        return Optional.empty();
    }

    public long count() {
        return repository.count();
    }

    /**
     * Matches short titles (CFO) to directory titles (Chief Financial Officer).
     */
    public static boolean rolesMatch(String claimedRole, String directoryRole) {
        if (claimedRole == null || directoryRole == null) {
            return false;
        }
        String claimed = claimedRole.trim().toLowerCase(Locale.ROOT);
        String directory = directoryRole.trim().toLowerCase(Locale.ROOT);
        if (claimed.equals(directory)) {
            return true;
        }
        if (claimed.equals("cfo") && directory.contains("chief financial")) {
            return true;
        }
        if (claimed.equals("ceo") && directory.contains("chief executive")) {
            return true;
        }
        if (claimed.equals("cto") && directory.contains("chief technology")) {
            return true;
        }
        if (claimed.equals("coo") && directory.contains("chief operating")) {
            return true;
        }
        if (directory.contains(claimed)) {
            return true;
        }
        return claimed.contains(directory);
    }
}

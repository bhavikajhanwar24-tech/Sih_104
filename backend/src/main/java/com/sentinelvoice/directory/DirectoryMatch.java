package com.sentinelvoice.directory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured directory resolution result (F4). Used by F7 rule engine.
 */
public record DirectoryMatch(
        UUID matchedEmployeeId,
        MatchType matchType,
        double confidence,
        String status,
        String roleKey,
        List<Map<String, Object>> authority,
        NumberProvenance numberProvenance,
        String fullName,
        String departmentName,
        boolean highAuthority,
        String employeeCode
) {
    public enum MatchType {
        NUMBER_EXACT,
        EXT_EXACT,
        NAME_FUZZY,
        NONE
    }

    public enum NumberProvenance {
        INTERNAL_EXT,
        KNOWN_MOBILE,
        EXTERNAL_UNKNOWN
    }

    public static DirectoryMatch none(NumberProvenance provenance) {
        return new DirectoryMatch(
                null,
                MatchType.NONE,
                0.0,
                null,
                null,
                List.of(),
                provenance == null ? NumberProvenance.EXTERNAL_UNKNOWN : provenance,
                null,
                null,
                false,
                null
        );
    }
}

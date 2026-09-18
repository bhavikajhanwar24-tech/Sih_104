package com.sentinelvoice.context;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.model.InteractionEdge;
import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.RelationshipQuery;
import com.sentinelvoice.repository.InteractionEdgeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Relationship-graph evidence family (Context §12).
 *
 * <p><b>Score formula</b> (coefficients from {@code sentinelvoice.context.relationship}):
 * <pre>
 *   S = clamp01(
 *         w_fc   * I(firstContact)
 *       + w_hier * min(1, hierarchyDistance / hierNorm)
 *       + w_off  * I(offHoursCall)
 *       + w_dur  * I(durationAnomaly)
 *   )
 * </pre>
 * Linear, documented, and tunable — not a chain of ad-hoc if/else score branches.
 */
@Service
public class RelationshipGraphService {

    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Kolkata");

    private final InteractionEdgeRepository edgeRepository;
    private final DirectoryService directoryService;
    private final SentinelProperties.RelationshipScoring weights;
    private final Clock clock;

    @Autowired
    public RelationshipGraphService(
            InteractionEdgeRepository edgeRepository,
            DirectoryService directoryService,
            SentinelProperties properties
    ) {
        this(edgeRepository, directoryService, properties, Clock.system(BANK_ZONE));
    }

    RelationshipGraphService(
            InteractionEdgeRepository edgeRepository,
            DirectoryService directoryService,
            SentinelProperties properties,
            Clock clock
    ) {
        this.edgeRepository = edgeRepository;
        this.directoryService = directoryService;
        this.weights = properties.context().relationship();
        this.clock = clock;
    }

    public RelationshipAssessment assess(RelationshipQuery query) {
        if (query == null || query.callerId() == null || query.recipientId() == null) {
            return insufficient();
        }
        String callerEmp = resolveEmployeeId(query.callerId());
        String calleeEmp = resolveEmployeeId(query.recipientId());
        if (callerEmp == null || calleeEmp == null) {
            return insufficient();
        }
        return assessEmployees(callerEmp, calleeEmp, null);
    }

    public RelationshipAssessment assessEmployees(
            String callerEmployeeId,
            String calleeEmployeeId,
            Integer callDurationSec
    ) {
        Optional<InteractionEdge> edgeOpt = edgeRepository
                .findByCallerEmployeeIdAndCalleeEmployeeId(callerEmployeeId, calleeEmployeeId);

        int interactionCount = edgeOpt.map(InteractionEdge::getInteractionCount).orElse(0);
        boolean firstContact = edgeOpt.isEmpty() || interactionCount <= 0;

        int hierarchyDistance = hierarchyDistance(callerEmployeeId, calleeEmployeeId);
        ZonedDateTime now = ZonedDateTime.now(clock);
        int hour = now.getHour();

        boolean offHoursCall = hour < weights.businessHourStart() || hour >= weights.businessHourEnd();
        if (edgeOpt.isPresent()) {
            int typical = edgeOpt.get().getTypicalHourOfDay();
            if (Math.abs(hour - typical) >= weights.typicalHourDeviationHours()) {
                offHoursCall = true;
            }
        }

        boolean durationAnomaly = false;
        if (callDurationSec != null && edgeOpt.isPresent()) {
            int typicalDur = Math.max(1, edgeOpt.get().getTypicalDurationSec());
            durationAnomaly = callDurationSec > typicalDur * weights.durationAnomalyRatio();
        }

        // Documented linear formula — coefficients live in SentinelProperties.
        double hierTerm = Math.min(1.0, (double) hierarchyDistance / weights.hierarchyNormalizeLevels());
        double score = clamp01(
                weights.weightFirstContact() * (firstContact ? 1.0 : 0.0)
                        + weights.weightHierarchy() * hierTerm
                        + weights.weightOffHours() * (offHoursCall ? 1.0 : 0.0)
                        + weights.weightDurationAnomaly() * (durationAnomaly ? 1.0 : 0.0)
        );

        List<String> reasons = new ArrayList<>();
        if (firstContact) {
            reasons.add("FIRST_CONTACT");
        } else {
            reasons.add("KNOWN_PAIR");
        }
        if (hierarchyDistance >= 3) {
            reasons.add("HIERARCHY_DISTANCE");
        }
        if (offHoursCall) {
            reasons.add("OFF_HOURS_CALL");
        }
        if (durationAnomaly) {
            reasons.add("DURATION_ANOMALY");
        }

        return new RelationshipAssessment(
                interactionCount,
                firstContact,
                hierarchyDistance,
                offHoursCall,
                durationAnomaly,
                score,
                List.copyOf(reasons)
        );
    }

    private int hierarchyDistance(String callerEmp, String calleeEmp) {
        DirectoryRecord caller = directoryService.findByEmployeeId(callerEmp).orElse(null);
        DirectoryRecord callee = directoryService.findByEmployeeId(calleeEmp).orElse(null);
        if (caller == null || callee == null) {
            return weights.hierarchyNormalizeLevels();
        }
        return Math.abs(caller.getHierarchyLevel() - callee.getHierarchyLevel());
    }

    private String resolveEmployeeId(String idOrCli) {
        if (idOrCli == null || idOrCli.isBlank()) {
            return null;
        }
        if (idOrCli.toUpperCase().startsWith("EMP-")) {
            return idOrCli;
        }
        return directoryService.findByCli(idOrCli)
                .map(DirectoryRecord::getEmployeeId)
                .orElse(idOrCli);
    }

    private static RelationshipAssessment insufficient() {
        return new RelationshipAssessment(
                0, true, Integer.MAX_VALUE, false, false, 0.50, List.of("INSUFFICIENT_IDENTITY")
        );
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

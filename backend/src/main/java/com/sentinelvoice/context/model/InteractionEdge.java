package com.sentinelvoice.context.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Directed interaction history between two directory employees (Context §12 stage [5]).
 */
@Entity
@Table(
        name = "interaction_edges",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_interaction_caller_callee",
                columnNames = {"caller_employee_id", "callee_employee_id"}
        )
)
public class InteractionEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "caller_employee_id", nullable = false, length = 32)
    private String callerEmployeeId;

    @Column(name = "callee_employee_id", nullable = false, length = 32)
    private String calleeEmployeeId;

    @Column(name = "interaction_count", nullable = false)
    private int interactionCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "typical_hour_of_day", nullable = false)
    private int typicalHourOfDay;

    @Column(name = "typical_duration_sec", nullable = false)
    private int typicalDurationSec;

    public InteractionEdge() {
    }

    public Long getId() {
        return id;
    }

    public String getCallerEmployeeId() {
        return callerEmployeeId;
    }

    public void setCallerEmployeeId(String callerEmployeeId) {
        this.callerEmployeeId = callerEmployeeId;
    }

    public String getCalleeEmployeeId() {
        return calleeEmployeeId;
    }

    public void setCalleeEmployeeId(String calleeEmployeeId) {
        this.calleeEmployeeId = calleeEmployeeId;
    }

    public int getInteractionCount() {
        return interactionCount;
    }

    public void setInteractionCount(int interactionCount) {
        this.interactionCount = interactionCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public int getTypicalHourOfDay() {
        return typicalHourOfDay;
    }

    public void setTypicalHourOfDay(int typicalHourOfDay) {
        this.typicalHourOfDay = typicalHourOfDay;
    }

    public int getTypicalDurationSec() {
        return typicalDurationSec;
    }

    public void setTypicalDurationSec(int typicalDurationSec) {
        this.typicalDurationSec = typicalDurationSec;
    }
}

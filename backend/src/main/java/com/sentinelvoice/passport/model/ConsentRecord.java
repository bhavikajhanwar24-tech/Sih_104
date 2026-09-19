package com.sentinelvoice.passport.model;

import java.time.Instant;

/**
 * Consent DTO (non-persistent). Persistence returns in F12.
 */
public class ConsentRecord {

    private Long id;
    private String employeeId;
    private String purpose;
    private String noticeVersion;
    private Instant grantedAt;
    private String grantedBy;
    private String method;
    private Instant withdrawnAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getNoticeVersion() {
        return noticeVersion;
    }

    public void setNoticeVersion(String noticeVersion) {
        this.noticeVersion = noticeVersion;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(Instant grantedAt) {
        this.grantedAt = grantedAt;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(Instant withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public boolean isActive() {
        return withdrawnAt == null;
    }
}

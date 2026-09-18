package com.sentinelvoice.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Seeded bank HR directory row (Context §12 stage [2]). Mock for LDAP/HRMS/CBS.
 */
@Entity
@Table(name = "directory_records")
public class DirectoryRecord {

    @Id
    @Column(name = "employee_id", nullable = false, length = 32)
    private String employeeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String department;

    @Column(name = "primary_cli", nullable = false)
    private String primaryCli;

    @Column
    private String extension;

    @Column(name = "verbal_authority_limit_inr", nullable = false)
    private double verbalAuthorityLimitInr;

    /** Comma-separated permitted instruction channels, e.g. {@code BRANCH,CBS}. */
    @Column(name = "permitted_channels", nullable = false)
    private String permittedChannels;

    @Column(name = "presence_status")
    private String presenceStatus;

    @Column(name = "calendar_location")
    private String calendarLocation;

    @Column(name = "manager_employee_id")
    private String managerEmployeeId;

    @Column(name = "hierarchy_level", nullable = false)
    private int hierarchyLevel;

    @Column(name = "passport_enrolled", nullable = false)
    private boolean passportEnrolled;

    public DirectoryRecord() {
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getPrimaryCli() {
        return primaryCli;
    }

    public void setPrimaryCli(String primaryCli) {
        this.primaryCli = primaryCli;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public double getVerbalAuthorityLimitInr() {
        return verbalAuthorityLimitInr;
    }

    public void setVerbalAuthorityLimitInr(double verbalAuthorityLimitInr) {
        this.verbalAuthorityLimitInr = verbalAuthorityLimitInr;
    }

    public String getPermittedChannels() {
        return permittedChannels;
    }

    public void setPermittedChannels(String permittedChannels) {
        this.permittedChannels = permittedChannels;
    }

    public String getPresenceStatus() {
        return presenceStatus;
    }

    public void setPresenceStatus(String presenceStatus) {
        this.presenceStatus = presenceStatus;
    }

    public String getCalendarLocation() {
        return calendarLocation;
    }

    public void setCalendarLocation(String calendarLocation) {
        this.calendarLocation = calendarLocation;
    }

    public String getManagerEmployeeId() {
        return managerEmployeeId;
    }

    public void setManagerEmployeeId(String managerEmployeeId) {
        this.managerEmployeeId = managerEmployeeId;
    }

    public int getHierarchyLevel() {
        return hierarchyLevel;
    }

    public void setHierarchyLevel(int hierarchyLevel) {
        this.hierarchyLevel = hierarchyLevel;
    }

    public boolean isPassportEnrolled() {
        return passportEnrolled;
    }

    public void setPassportEnrolled(boolean passportEnrolled) {
        this.passportEnrolled = passportEnrolled;
    }
}

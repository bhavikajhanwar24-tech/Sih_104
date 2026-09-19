package com.sentinelvoice.identity.model;

/**
 * Directory row DTO (non-persistent). JPA persistence returns in F4.
 */
public class DirectoryRecord {

    private String employeeId;
    private String name;
    private String role;
    private String department;
    private String primaryCli;
    private String extension;
    private double verbalAuthorityLimitInr;
    private String permittedChannels;
    private int hierarchyLevel;
    private boolean passportEnrolled;
    private String expectedPresence;

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

    public String getExpectedPresence() {
        return expectedPresence;
    }

    public void setExpectedPresence(String expectedPresence) {
        this.expectedPresence = expectedPresence;
    }
}

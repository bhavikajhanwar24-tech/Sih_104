package com.sentinelvoice.analytics;

public class AnalyticsException extends RuntimeException {

    private final String code;

    public AnalyticsException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

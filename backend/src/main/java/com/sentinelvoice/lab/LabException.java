package com.sentinelvoice.lab;

public class LabException extends RuntimeException {
    private final String code;

    public LabException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package com.sentinelvoice.demo;

public class DemoSeedException extends RuntimeException {

    private final String code;

    public DemoSeedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

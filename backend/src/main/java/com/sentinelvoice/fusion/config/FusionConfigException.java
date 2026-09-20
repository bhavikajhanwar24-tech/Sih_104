package com.sentinelvoice.fusion.config;

public class FusionConfigException extends RuntimeException {

    private final String code;

    public FusionConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

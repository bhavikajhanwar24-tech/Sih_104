package com.sentinelvoice.policy.compile;

public class PolicyCompileException extends RuntimeException {

    private final String code;

    public PolicyCompileException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

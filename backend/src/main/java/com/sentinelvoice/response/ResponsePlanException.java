package com.sentinelvoice.response;

public class ResponsePlanException extends RuntimeException {

    private final String code;

    public ResponsePlanException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

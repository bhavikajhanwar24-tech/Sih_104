package com.sentinelvoice.actuation;

/**
 * Outcome of one actuation attempt. Never throws out of the intervention path.
 */
public record ActuationResult(Status status, String detail) {

    public enum Status {
        SUCCESS,
        FAILURE,
        UNSUPPORTED
    }

    public static ActuationResult success(String detail) {
        return new ActuationResult(Status.SUCCESS, detail == null ? "" : detail);
    }

    public static ActuationResult failure(String detail) {
        return new ActuationResult(Status.FAILURE, detail == null ? "" : detail);
    }

    public static ActuationResult unsupported(String detail) {
        return new ActuationResult(Status.UNSUPPORTED, detail == null ? "" : detail);
    }
}

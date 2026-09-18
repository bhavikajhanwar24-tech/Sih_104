package com.sentinelvoice.ingest;

public class FrameValidationException extends RuntimeException {

    public FrameValidationException(String message) {
        super(message);
    }

    public FrameValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

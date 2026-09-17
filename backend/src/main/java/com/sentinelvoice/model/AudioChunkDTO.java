package com.sentinelvoice.model;

import java.util.Base64;

public record AudioChunkDTO(
        String sessionId,
        String contentType,
        String data,
        long timestamp
) {
    public byte[] toBytes() {
        if (data == null || data.isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(data);
    }
}

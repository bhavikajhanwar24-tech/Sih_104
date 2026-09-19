package com.sentinelvoice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class MessageDigestUtil {
    private MessageDigestUtil() {
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}

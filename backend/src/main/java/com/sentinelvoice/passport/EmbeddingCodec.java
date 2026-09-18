package com.sentinelvoice.passport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Codec for 192-d ECAPA embeddings: float32 LE ↔ storage bytes (Base64-wrapped).
 */
public final class EmbeddingCodec {

    public static final int DIM = 192;

    private EmbeddingCodec() {
    }

    /** Encode 192 floats to Base64-wrapped little-endian float32 bytes for DB storage. */
    public static byte[] encode(float[] embedding) {
        requireDim(embedding);
        byte[] raw = toFloat32Le(embedding);
        return Base64.getEncoder().encode(raw);
    }

    public static float[] decode(byte[] stored) {
        if (stored == null || stored.length == 0) {
            throw new IllegalArgumentException("empty embedding storage");
        }
        byte[] raw = Base64.getDecoder().decode(stored);
        return fromFloat32Le(raw);
    }

    public static String toBase64(float[] embedding) {
        return Base64.getEncoder().encodeToString(toFloat32Le(embedding));
    }

    public static float[] fromBase64(String b64) {
        return fromFloat32Le(Base64.getDecoder().decode(b64));
    }

    public static byte[] sha256RawFloats(float[] embedding) {
        requireDim(embedding);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(toFloat32Le(embedding));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(float[] embedding) {
        byte[] digest = sha256RawFloats(embedding);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static double cosine(float[] a, float[] b) {
        requireDim(a);
        requireDim(b);
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < DIM; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb) + 1e-12;
        return Math.max(-1.0, Math.min(1.0, dot / denom));
    }

    private static byte[] toFloat32Le(float[] embedding) {
        ByteBuffer buf = ByteBuffer.allocate(DIM * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : embedding) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    private static float[] fromFloat32Le(byte[] raw) {
        if (raw.length != DIM * Float.BYTES) {
            throw new IllegalArgumentException(
                    "expected " + (DIM * Float.BYTES) + " float bytes, got " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    private static void requireDim(float[] embedding) {
        if (embedding == null || embedding.length != DIM) {
            throw new IllegalArgumentException(
                    "embedding must be length " + DIM + ", got "
                            + (embedding == null ? "null" : embedding.length));
        }
    }
}

package com.sentinelvoice.response.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM encryption for tenant integration secrets. Key from {@code APP_ENCRYPTION_KEY}.
 */
@Component
public class SecretBox {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${APP_ENCRYPTION_KEY:}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            // Dev fallback — derived from a fixed label so local boots without .env still work.
            this.key = deriveKey("dev-only-sentinelvoice-encryption-key-change-me");
        } else {
            this.key = deriveKey(encryptionKey.trim());
        }
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(byte[] blob) {
        if (blob == null || blob.length <= GCM_IV_LENGTH) {
            return null;
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(blob, GCM_IV_LENGTH, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    public String encryptToBase64(String plaintext) {
        byte[] enc = encrypt(plaintext);
        return enc == null ? null : Base64.getEncoder().encodeToString(enc);
    }

    public String decryptFromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        return decrypt(Base64.getDecoder().decode(base64.trim()));
    }

    public byte[] encryptBytes(byte[] plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();
        } catch (Exception e) {
            throw new IllegalStateException("encryptBytes failed", e);
        }
    }

    public byte[] decryptBytes(byte[] blob) {
        if (blob == null || blob.length <= GCM_IV_LENGTH) {
            return null;
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(blob, GCM_IV_LENGTH, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("decryptBytes failed", e);
        }
    }

    private static SecretKey deriveKey(String material) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(dig, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

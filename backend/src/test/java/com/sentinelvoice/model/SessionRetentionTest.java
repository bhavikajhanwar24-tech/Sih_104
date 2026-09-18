package com.sentinelvoice.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executable form of the privacy claim in Context §6.3:
 * the Decision Plane session model must never retain raw PCM.
 * If this test fails, the "zero raw-audio retention" claim is false in code.
 */
class SessionRetentionTest {

    @Test
    void callSessionDeclaresNoAudioFields() {
        for (Field field : CallSession.class.getDeclaredFields()) {
            String name = field.getName();
            if (name.toLowerCase().contains("audio")) {
                fail("CallSession declares audio-named field: " + name);
            }
            Class<?> type = field.getType();
            if (type.equals(byte[].class) || type.equals(Byte[].class) || type.equals(byte.class)) {
                fail("CallSession declares byte[] field: " + name);
            }
            if (Deque.class.isAssignableFrom(type) && isByteDeque(field.getGenericType())) {
                fail("CallSession declares Deque<byte[]> field: " + name);
            }
        }
    }

    private static boolean isByteDeque(Type genericType) {
        if (genericType instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            return args.length == 1 && (args[0].equals(byte[].class) || args[0].equals(Byte[].class));
        }
        return false;
    }
}

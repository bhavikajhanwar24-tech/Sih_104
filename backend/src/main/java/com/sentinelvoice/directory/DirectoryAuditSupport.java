package com.sentinelvoice.directory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Audit payload helpers — redact phones to last 3 digits.
 */
public final class DirectoryAuditSupport {

    private static final Pattern PHONE_LIKE = Pattern.compile("^\\+?[0-9]{7,15}$");

    private DirectoryAuditSupport() {
    }

    public static String redactPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 3) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 3);
    }

    @SuppressWarnings("unchecked")
    public static Object redactValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("phone") || k.contains("e164") || k.equals("cli") || k.contains("mobile")) {
            if (value instanceof String s) {
                return redactPhone(s);
            }
        }
        if (value instanceof String s && PHONE_LIKE.matcher(s.replaceAll("[\\s-]", "")).matches()) {
            return redactPhone(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), redactValue(String.valueOf(e.getKey()), e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(redactValue(key, item));
            }
            return out;
        }
        return value;
    }

    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changed = new LinkedHashMap<>();
        if (after != null) {
            for (Map.Entry<String, Object> e : after.entrySet()) {
                Object prev = before == null ? null : before.get(e.getKey());
                if (!Objects.equals(prev, e.getValue())) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("from", redactValue(e.getKey(), prev));
                    pair.put("to", redactValue(e.getKey(), e.getValue()));
                    changed.put(e.getKey(), pair);
                }
            }
        }
        if (before != null) {
            for (String key : before.keySet()) {
                if (after == null || !after.containsKey(key)) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("from", redactValue(key, before.get(key)));
                    pair.put("to", null);
                    changed.put(key, pair);
                }
            }
        }
        return changed;
    }
}

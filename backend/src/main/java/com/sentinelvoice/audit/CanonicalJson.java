package com.sentinelvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON for audit hashing: sorted keys, no whitespace, doubles at exactly 6 decimal places.
 */
@Component
public class CanonicalJson {

    private final ObjectMapper mapper;

    public CanonicalJson() {
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    public String serialize(Map<String, ?> payload) {
        try {
            Object normalized = normalize(payload == null ? Map.of() : payload);
            return mapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical JSON serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, LinkedHashMap.class);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical JSON deserialization failed", e);
        }
    }

    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sorted.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            for (Object element : iterable) {
                list.add(normalize(element));
            }
            return list;
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(6, RoundingMode.HALF_UP);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(6, RoundingMode.HALF_UP);
        }
        return value;
    }
}

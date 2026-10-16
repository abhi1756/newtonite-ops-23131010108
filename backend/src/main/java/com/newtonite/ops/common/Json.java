package com.newtonite.ops.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers for JSONB columns. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise JSON", e);
        }
    }

    public JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse JSON", e);
        }
    }

    /** Builds a map from key/value pairs; unlike Map.of it keeps insertion order and allows null values. */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

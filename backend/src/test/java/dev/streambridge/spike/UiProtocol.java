package dev.streambridge.spike;

import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

/** Versioned local worker protocol. Human console output is NOT an API. */
final class UiProtocol {
    static final String PREFIX = "STREAMBRIDGE_EVENT ";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static String json(Object value) { return JSON.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String value) { return JSON.readValue(value, Map.class); }
    static synchronized void emit(String type, Object data) {
        System.out.println(PREFIX + json(Map.of("version", 1, "type", type, "data", data)));
    }
}

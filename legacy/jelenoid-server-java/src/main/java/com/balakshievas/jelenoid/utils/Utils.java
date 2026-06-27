package com.balakshievas.jelenoid.utils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Utils {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> source, String key, Map<String, Object> defaultValue) {
        if(source != null) {
            Object value = source.get(key);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getList(Map<String, Object> source, String key) {
        if(source != null) {
            Object value = source.get(key);
            if (value instanceof List) {
                return (List<Map<String, Object>>) value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static String getString(Map<String, Object> source, String key) {
        if(source != null) {
            Object value = source.get(key);
            if (value instanceof String) {
                return (String) value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getListOfMaps(Map<String, Object> source, String key,
                                                    List<Map<String, Object>> defaultValue) {
        Object value = source.get(key);

        if (value instanceof List<?> list) {
            if (list.isEmpty() || list.getFirst() instanceof Map) {
                return (List<Map<String, Object>>) value;
            }
        }

        if (defaultValue != null) {
            return defaultValue;
        }
        return List.of(Collections.emptyMap());
    }
}



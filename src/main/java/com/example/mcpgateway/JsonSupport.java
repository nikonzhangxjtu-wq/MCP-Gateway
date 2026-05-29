package com.example.mcpgateway;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonSupport {
    private JsonSupport() {
    }

    public static String string(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                builder.append(string(entry.getKey().toString())).append(":").append(string(entry.getValue()));
                if (iterator.hasNext()) {
                    builder.append(",");
                }
            }
            return builder.append("}").toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder("[");
            Iterator<?> iterator = collection.iterator();
            while (iterator.hasNext()) {
                builder.append(string(iterator.next()));
                if (iterator.hasNext()) {
                    builder.append(",");
                }
            }
            return builder.append("]").toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    public static String extractString(String json, String field) {
        String marker = "\"" + field + "\"";
        int fieldIndex = json.indexOf(marker);
        if (fieldIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', fieldIndex + marker.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        boolean escaped = false;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == '"' && !escaped) {
                break;
            }
            escaped = ch == '\\' && !escaped;
            if (ch != '\\') {
                escaped = false;
            }
            end++;
        }
        return json.substring(start + 1, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public static Map<String, Object> extractArguments(String json) {
        int argsIndex = json.indexOf("\"arguments\"");
        if (argsIndex < 0) {
            return Map.of();
        }
        int objectStart = json.indexOf('{', argsIndex);
        if (objectStart < 0) {
            return Map.of();
        }
        int objectEnd = findMatchingBrace(json, objectStart);
        if (objectEnd < 0) {
            return Map.of();
        }
        return parseFlatStringObject(json.substring(objectStart + 1, objectEnd));
    }

    private static Map<String, Object> parseFlatStringObject(String objectBody) {
        Map<String, Object> values = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < objectBody.length()) {
            int keyStart = objectBody.indexOf('"', cursor);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = objectBody.indexOf('"', keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = objectBody.substring(keyStart + 1, keyEnd);
            int colon = objectBody.indexOf(':', keyEnd + 1);
            if (colon < 0) {
                break;
            }
            int valueStart = objectBody.indexOf('"', colon + 1);
            if (valueStart < 0) {
                break;
            }
            int valueEnd = objectBody.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                break;
            }
            values.put(key, objectBody.substring(valueStart + 1, valueEnd));
            cursor = valueEnd + 1;
        }
        return values;
    }

    private static int findMatchingBrace(String json, int objectStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = objectStart; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"' && !escaped) {
                inString = !inString;
            }
            if (!inString) {
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            escaped = ch == '\\' && !escaped;
            if (ch != '\\') {
                escaped = false;
            }
        }
        return -1;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

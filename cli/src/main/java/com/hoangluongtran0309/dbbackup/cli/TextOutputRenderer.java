package com.hoangluongtran0309.dbbackup.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Plain, deterministic terminal output; JSON remains the automation format. */
final class TextOutputRenderer {
    private static final String EMPTY = "(none)";

    private TextOutputRenderer() { }

    static String render(JsonNode value) {
        StringBuilder output = new StringBuilder();
        append(value, output, 0);
        return output.toString().stripTrailing();
    }

    static String renderError(JsonNode envelope, String fallback) {
        JsonNode errors = envelope == null ? null : envelope.path("error").path("errors");
        if (errors == null || !errors.isArray() || errors.isEmpty()) return fallback;
        StringBuilder output = new StringBuilder("Validation failed:");
        for (JsonNode error : errors) {
            String field = error.path("field").isNull() || error.path("field").asText().isBlank()
                    ? "request" : error.path("field").asText();
            output.append(System.lineSeparator()).append("  ")
                    .append(field).append(": ").append(error.path("message").asText());
        }
        return output.toString();
    }

    private static void append(JsonNode value, StringBuilder output, int indent) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            line(output, indent, "-");
        } else if (value.isArray()) {
            appendArray(value, output, indent);
        } else if (value.isObject()) {
            appendObject(value, output, indent);
        } else {
            line(output, indent, scalar(value));
        }
    }

    private static void appendObject(JsonNode object, StringBuilder output, int indent) {
        List<String> nested = new ArrayList<>();
        object.properties().forEach(entry -> {
            if (entry.getValue().isContainerNode()) {
                nested.add(entry.getKey());
            } else {
                line(output, indent, entry.getKey() + ": " + scalar(entry.getValue()));
            }
        });
        for (String field : nested) {
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append(System.lineSeparator());
            line(output, indent, field + ":");
            append(object.path(field), output, indent + 2);
        }
        if (object.isEmpty()) line(output, indent, EMPTY);
    }

    private static void appendArray(JsonNode array, StringBuilder output, int indent) {
        if (array.isEmpty()) {
            line(output, indent, EMPTY);
            return;
        }
        boolean objects = true;
        for (JsonNode value : array) objects &= value.isObject();
        if (!objects) {
            for (JsonNode value : array) line(output, indent, "- " + scalar(value));
            return;
        }

        Set<String> columns = new LinkedHashSet<>();
        Set<String> nestedColumns = new LinkedHashSet<>();
        for (JsonNode row : array) {
            row.properties().forEach(entry -> {
                if (entry.getValue().isContainerNode()) {
                    nestedColumns.add(entry.getKey());
                    columns.remove(entry.getKey());
                } else if (!nestedColumns.contains(entry.getKey())) {
                    columns.add(entry.getKey());
                }
            });
        }
        if (columns.isEmpty()) {
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) output.append(System.lineSeparator());
                appendObject(array.get(index), output, indent);
            }
            return;
        }

        List<String> fields = List.copyOf(columns);
        int[] widths = new int[fields.size()];
        for (int column = 0; column < fields.size(); column++) {
            widths[column] = heading(fields.get(column)).length();
            for (JsonNode row : array) {
                widths[column] = Math.max(widths[column], scalar(row.path(fields.get(column))).length());
            }
        }
        appendRow(output, indent, fields.stream().map(TextOutputRenderer::heading).toList(), widths);
        appendRow(output, indent, fields.stream().map(field -> "-".repeat(widths[fields.indexOf(field)]))
                .toList(), widths);
        for (JsonNode row : array) {
            appendRow(output, indent, fields.stream().map(field -> scalar(row.path(field))).toList(), widths);
        }
    }

    private static void appendRow(StringBuilder output, int indent, List<String> values, int[] widths) {
        output.append(" ".repeat(indent));
        for (int column = 0; column < values.size(); column++) {
            if (column > 0) output.append("  ");
            output.append(values.get(column));
            if (column + 1 < values.size()) {
                output.append(" ".repeat(widths[column] - values.get(column).length()));
            }
        }
        output.append(System.lineSeparator());
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "-";
        if (value.isTextual()) return value.asText().replace("\r", "\\r").replace("\n", "\\n");
        if (value.isValueNode()) return value.asText();
        return value.toString();
    }

    private static String heading(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toUpperCase(Locale.ROOT);
    }

    private static void line(StringBuilder output, int indent, String value) {
        output.append(" ".repeat(indent)).append(value).append(System.lineSeparator());
    }
}

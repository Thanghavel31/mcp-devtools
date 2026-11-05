package com.project.mcpServer;

import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class ToolAuditLog {

    private static final String AUDIT_FILE = "mcp-audit.log";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());

    public void log(String tool, Map<String, String> params, long durationMs, boolean success) {
        String timestamp = FMT.format(Instant.now());
        String sanitizedParams = params.entrySet().stream()
                .filter(e -> !e.getKey().toLowerCase().contains("token"))
                .map(e -> e.getKey() + "=" + truncate(e.getValue(), 100))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        String line = String.format("[%s] tool=%s params={%s} duration=%dms success=%s",
                timestamp, tool, sanitizedParams, durationMs, success);

        try (PrintWriter pw = new PrintWriter(new FileWriter(AUDIT_FILE, true))) {
            pw.println(line);
        } catch (IOException e) {
            // Swallow — audit failure must never break tool execution
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "null";
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}

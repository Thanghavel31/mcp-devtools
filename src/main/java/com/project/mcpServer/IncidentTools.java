package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IncidentTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<Map<String, String>> incidents = new ArrayList<>();

    public IncidentTools() {
        // Simulated production incidents for an OTA/IoT platform
        incidents.add(incident("INC-2001", "SEV1", "Open",
                "OTA campaign stalled — Kafka consumer lag >100k",
                "Thanghavel",
                "2026-11-03 14:30",
                "Fleet OTA campaign CAMP-4521 stopped progressing. Kafka consumer group 'ota-command-processor' showing lag of 120k messages. Affected vehicles: ~15,000. Root cause under investigation — suspected partition rebalance storm after EKS pod scaling event.",
                "cosmos-ota-service"));

        incidents.add(incident("INC-2002", "SEV2", "Mitigated",
                "Telemetry ingestion latency spike — p99 > 5s",
                "Thanghavel",
                "2026-11-02 09:15",
                "ELK ingestion pipeline p99 latency spiked from 200ms to 5.2s. Caused by a Logstash GC pause. Mitigated by restarting Logstash nodes and increasing heap to 8GB. Long-term fix: migrate to Fluent Bit.",
                "cosmos-telemetry-service"));

        incidents.add(incident("INC-2003", "SEV1", "Resolved",
                "PostgreSQL connection pool exhaustion — vehicle-status API 503s",
                "Thanghavel",
                "2026-10-28 22:00",
                "HikariCP pool (max=50) exhausted due to slow query on vehicle_events table (sequential scan on 500M rows). 30% of vehicle-status API requests returned 503. Fixed by adding composite index (vehicle_id, event_timestamp) and increasing pool to 80. MTTR: 45 min.",
                "cosmos-vehicle-status"));

        incidents.add(incident("INC-2004", "SEV3", "Resolved",
                "Stale Redis cache returning outdated vehicle firmware version",
                "Unassigned",
                "2026-10-25 11:30",
                "Redis TTL for firmware version cache was set to 24h. After OTA update, vehicles showed old firmware version in dashboard for up to 24h. Fixed by adding cache invalidation on OTA completion event.",
                "cosmos-fleet-monitor"));

        incidents.add(incident("INC-2005", "SEV2", "Open",
                "mTLS certificate expiry — 200 devices failing to connect",
                "Thanghavel",
                "2026-11-03 16:00",
                "Batch of device certificates provisioned in 2025-11 are expiring. 200 devices in EU region failing mTLS handshake. Need emergency certificate rotation via IoT Hub.",
                "cosmos-device-connectivity"));
    }

    @McpTool(description = "Get recent production incidents filtered by severity or status. Use this during standups, on-call handoffs, or to assess production health.")
    public String getRecentIncidents(
            @McpToolParam(description = "Filter by severity: SEV1, SEV2, SEV3, or 'all'") String severity,
            @McpToolParam(description = "Filter by status: Open, Mitigated, Resolved, or 'all'") String status) {

        List<Map<String, String>> results = incidents.stream()
                .filter(i -> "all".equalsIgnoreCase(severity) || i.get("severity").equalsIgnoreCase(severity))
                .filter(i -> "all".equalsIgnoreCase(status) || i.get("status").equalsIgnoreCase(status))
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "No incidents found matching the filters.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d incident(s):\n\n", results.size()));
        for (Map<String, String> i : results) {
            sb.append(String.format("[%s] %s | %s | Service: %s\n  Title: %s\n  Owner: %s | Opened: %s\n\n",
                    i.get("id"), i.get("severity"), i.get("status"), i.get("service"),
                    i.get("title"), i.get("owner"), i.get("opened")));
        }
        return sb.toString();
    }

    @McpTool(description = "Create a new production incident. Use this when you detect a production issue and need to formally track it with severity, ownership, and affected service.")
    public String createIncident(
            @McpToolParam(description = "Severity: SEV1, SEV2, or SEV3") String severity,
            @McpToolParam(description = "Short title describing the incident") String title,
            @McpToolParam(description = "Detailed description including impact and initial findings") String description,
            @McpToolParam(description = "Affected microservice name") String service,
            @McpToolParam(description = "Incident owner/on-call engineer name") String owner) {

        String id = "INC-" + (2000 + incidents.size() + 1);
        String opened = LocalDateTime.now().format(FMT);
        incidents.add(incident(id, severity.toUpperCase(), "Open", title, owner, opened, description, service));

        return String.format("Incident created!\n\n[%s] %s | Status: Open | Service: %s\nTitle: %s\nOwner: %s\nOpened: %s\n\nNext steps:\n1. Join bridge call if SEV1\n2. Check runbook: searchConfluencePages('incident response')\n3. Post update in #cosmos-incidents",
                id, severity.toUpperCase(), service, title, owner, opened);
    }

    @McpTool(description = "Get full details of a specific incident by ID. Use this to review incident timeline, root cause, and affected services during RCA.")
    public String getIncidentDetails(
            @McpToolParam(description = "Incident ID, e.g. INC-2001") String incidentId) {

        return incidents.stream()
                .filter(i -> i.get("id").equalsIgnoreCase(incidentId))
                .findFirst()
                .map(i -> String.format("[%s] %s | %s\nService: %s\nOwner: %s\nOpened: %s\n\nTitle: %s\n\nDetails:\n%s",
                        i.get("id"), i.get("severity"), i.get("status"), i.get("service"),
                        i.get("owner"), i.get("opened"), i.get("title"), i.get("description")))
                .orElse("Incident " + incidentId + " not found.");
    }

    private Map<String, String> incident(String id, String severity, String status, String title,
                                          String owner, String opened, String description, String service) {
        Map<String, String> i = new LinkedHashMap<>();
        i.put("id", id);
        i.put("severity", severity);
        i.put("status", status);
        i.put("title", title);
        i.put("owner", owner);
        i.put("opened", opened);
        i.put("description", description);
        i.put("service", service);
        return i;
    }
}

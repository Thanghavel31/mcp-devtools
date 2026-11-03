package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JiraTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<Map<String, String>> tickets = new ArrayList<>();

    public JiraTools() {
        // Simulated Jira tickets reflecting a real OTA/connected-vehicle project
        tickets.add(ticket("COSMOS-1001", "BUG", "P1", "Open",
                "OTA update timeout on ECU firmware v3.2.1",
                "Thanghavel", "Firmware updates fail with timeout after 120s on 2024 models. Kafka consumer lag spikes during peak hours.",
                "2026-11-01 09:15"));
        tickets.add(ticket("COSMOS-1002", "STORY", "P2", "In Progress",
                "Migrate telemetry pipeline to virtual threads (Java 21)",
                "Thanghavel", "Replace platform thread pools with virtual threads in telemetry ingestion service to improve throughput under 5TB/day load.",
                "2026-10-28 14:30"));
        tickets.add(ticket("COSMOS-1003", "BUG", "P1", "Open",
                "Kafka consumer rebalance storm in fleet-monitoring service",
                "Unassigned", "Frequent rebalances causing message duplication. Suspect session.timeout.ms misconfiguration.",
                "2026-11-02 11:00"));
        tickets.add(ticket("COSMOS-1004", "TASK", "P3", "Done",
                "Add circuit breaker to vehicle-status API",
                "Thanghavel", "Implemented Resilience4j circuit breaker with 50% failure threshold. Fallback returns cached status from Redis.",
                "2026-10-20 10:00"));
        tickets.add(ticket("COSMOS-1005", "STORY", "P2", "Open",
                "Design backward-compatible OTA schema for legacy fleet",
                "Thanghavel", "Need schema evolution strategy supporting v1 and v2 vehicles without downtime. Evaluate Protobuf vs Avro.",
                "2026-11-03 08:45"));
        tickets.add(ticket("COSMOS-1006", "BUG", "P2", "In Progress",
                "PostgreSQL slow query on vehicle_events table",
                "Thanghavel", "Query plan shows sequential scan on 500M row table. Need composite index on (vehicle_id, event_timestamp).",
                "2026-10-30 16:20"));
        tickets.add(ticket("COSMOS-1007", "TASK", "P3", "Open",
                "Set up distributed tracing with OpenTelemetry",
                "Unassigned", "Add trace propagation across OTA orchestrator, Kafka, and downstream microservices.",
                "2026-11-01 13:00"));
        tickets.add(ticket("COSMOS-1008", "STORY", "P1", "In Progress",
                "Implement exactly-once delivery for OTA command pipeline",
                "Thanghavel", "Current at-least-once delivery causes duplicate firmware flash commands. Implement idempotency keys with Kafka transactions.",
                "2026-10-25 09:30"));
    }

    @McpTool(description = "Search Jira tickets by status, priority, assignee, or type. Returns matching tickets from the COSMOS OTA project. Use this to check workload, find open bugs, or track sprint progress.")
    public String searchJiraTickets(
            @McpToolParam(description = "Filter by status: Open, In Progress, Done, or 'all'") String status,
            @McpToolParam(description = "Filter by priority: P1, P2, P3, or 'all'") String priority,
            @McpToolParam(description = "Filter by assignee name, or 'all'") String assignee) {

        List<Map<String, String>> results = tickets.stream()
                .filter(t -> "all".equalsIgnoreCase(status) || t.get("status").equalsIgnoreCase(status))
                .filter(t -> "all".equalsIgnoreCase(priority) || t.get("priority").equalsIgnoreCase(priority))
                .filter(t -> "all".equalsIgnoreCase(assignee) || t.get("assignee").equalsIgnoreCase(assignee))
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "No tickets found matching the filters.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d ticket(s):\n\n", results.size()));
        for (Map<String, String> t : results) {
            sb.append(String.format("[%s] %s | %s | %s | Assignee: %s\n  Summary: %s\n  Created: %s\n\n",
                    t.get("key"), t.get("type"), t.get("priority"), t.get("status"),
                    t.get("assignee"), t.get("summary"), t.get("created")));
        }
        return sb.toString();
    }

    @McpTool(description = "Create a new Jira ticket in the COSMOS project. Use this to log bugs, tasks, or stories from an incident or code review finding.")
    public String createJiraTicket(
            @McpToolParam(description = "Ticket type: BUG, STORY, or TASK") String type,
            @McpToolParam(description = "Priority: P1, P2, or P3") String priority,
            @McpToolParam(description = "Short summary of the issue") String summary,
            @McpToolParam(description = "Detailed description") String description,
            @McpToolParam(description = "Assignee name") String assignee) {

        String key = "COSMOS-" + (1000 + tickets.size() + 1);
        String created = LocalDateTime.now().format(FMT);
        Map<String, String> newTicket = ticket(key, type.toUpperCase(), priority.toUpperCase(), "Open",
                summary, assignee, description, created);
        tickets.add(newTicket);

        return String.format("Ticket created successfully!\n\n[%s] %s | %s | Status: Open\nSummary: %s\nAssignee: %s\nCreated: %s",
                key, type.toUpperCase(), priority.toUpperCase(), summary, assignee, created);
    }

    @McpTool(description = "Get full details of a specific Jira ticket by its key (e.g., COSMOS-1001). Use this to deep-dive into a bug or check ticket context before a standup or RCA.")
    public String getJiraTicket(
            @McpToolParam(description = "Jira ticket key, e.g. COSMOS-1001") String ticketKey) {

        return tickets.stream()
                .filter(t -> t.get("key").equalsIgnoreCase(ticketKey))
                .findFirst()
                .map(t -> String.format("[%s] %s | %s | %s\nAssignee: %s\nCreated: %s\n\nSummary: %s\n\nDescription:\n%s",
                        t.get("key"), t.get("type"), t.get("priority"), t.get("status"),
                        t.get("assignee"), t.get("created"), t.get("summary"), t.get("description")))
                .orElse("Ticket " + ticketKey + " not found.");
    }

    private Map<String, String> ticket(String key, String type, String priority, String status,
                                        String summary, String assignee, String description, String created) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("key", key);
        t.put("type", type);
        t.put("priority", priority);
        t.put("status", status);
        t.put("summary", summary);
        t.put("assignee", assignee);
        t.put("description", description);
        t.put("created", created);
        return t;
    }
}

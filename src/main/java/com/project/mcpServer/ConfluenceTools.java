package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfluenceTools {

    private final List<Map<String, String>> pages = new ArrayList<>();

    public ConfluenceTools() {
        // Simulated Confluence pages for a real automotive IoT team
        pages.add(page("COSMOS-RB-001", "OTA Rollback Runbook",
                "Engineering/Runbooks",
                "Thanghavel",
                "2026-10-15",
                """
                ## OTA Rollback Runbook
                
                ### When to trigger
                - OTA success rate drops below 95% for any ECU type
                - P1 incident opened for firmware regression
                
                ### Steps
                1. Pause the OTA campaign via Campaign Manager API: POST /api/v1/campaigns/{id}/pause
                2. Verify Kafka consumer lag is stable (Grafana dashboard: OTA-Monitoring)
                3. Trigger rollback: POST /api/v1/rollback with payload {vehicleGroup, targetFirmwareVersion}
                4. Monitor rollback progress in ELK: index=ota-events, filter=rollback
                5. Notify stakeholders in #cosmos-incidents Slack channel
                
                ### Escalation
                - If rollback fails for >5% of vehicles, escalate to L2 on-call
                - Contact cloud infra team if EKS pod restarts exceed threshold
                """));

        pages.add(page("COSMOS-ARCH-001", "Telemetry Pipeline Architecture",
                "Engineering/Architecture",
                "Thanghavel",
                "2026-09-20",
                """
                ## Telemetry Pipeline Architecture
                
                ### Overview
                Ingests ~5TB/day of vehicle telemetry data from 1M+ connected vehicles.
                
                ### Components
                - **Ingestion Layer**: Spring Boot services on EKS receiving MQTT messages via AWS IoT Core
                - **Streaming Layer**: Kafka (3 brokers, 12 partitions per topic) with exactly-once semantics
                - **Processing Layer**: Spark Streaming jobs for real-time aggregation
                - **Storage Layer**: PostgreSQL (hot data, 30 days), S3 (cold storage, Parquet)
                - **Monitoring**: ELK Stack with custom dashboards for fleet health
                
                ### Key Design Decisions
                - Chose Kafka over SQS for ordering guarantees per vehicle_id
                - Partitioning strategy: hash(vehicle_id) % 12 for even distribution
                - Circuit breaker on downstream calls (Resilience4j, 50% threshold)
                """));

        pages.add(page("COSMOS-ARCH-002", "OTA Exactly-Once Delivery Design",
                "Engineering/Architecture",
                "Thanghavel",
                "2026-10-25",
                """
                ## Exactly-Once Delivery for OTA Commands
                
                ### Problem
                At-least-once delivery caused duplicate firmware flash commands, leading to unnecessary ECU reboots.
                
                ### Solution
                1. **Idempotency Key**: SHA-256(vehicle_id + firmware_version + campaign_id)
                2. **Kafka Transactions**: enable.idempotence=true, transactional.id per producer
                3. **Consumer Side**: Dedup table in PostgreSQL with idempotency_key as unique constraint
                4. **Fallback**: Redis cache (TTL=24h) for fast duplicate detection before DB check
                
                ### Trade-offs
                - Slight latency increase (~15ms) per message for dedup check
                - Accepted: consistency > availability for safety-critical OTA commands (CAP theorem)
                """));

        pages.add(page("COSMOS-OPS-001", "Production Incident Response Playbook",
                "Engineering/Operations",
                "Thanghavel",
                "2026-10-01",
                """
                ## Incident Response Playbook
                
                ### Severity Levels
                - **SEV1**: Complete service outage or data loss. Response: 15 min. Bridge call.
                - **SEV2**: Degraded service affecting >10% users. Response: 30 min.
                - **SEV3**: Minor issue, workaround available. Response: 4 hours.
                
                ### On-Call Rotation
                - Primary: Check PagerDuty schedule
                - Escalation: Engineering Manager → VP Engineering
                
                ### RCA Template
                1. Timeline of events
                2. Root cause (5 Whys analysis)
                3. Impact assessment (vehicles affected, duration)
                4. Remediation steps taken
                5. Long-term prevention action items (create Jira tickets)
                
                ### Key Dashboards
                - Grafana: OTA-Monitoring, Fleet-Health, Kafka-Lag
                - ELK: ota-events, vehicle-telemetry, error-logs
                """));

        pages.add(page("COSMOS-ONBOARD-001", "New Developer Onboarding Guide",
                "Engineering/Onboarding",
                "Thanghavel",
                "2026-08-15",
                """
                ## COSMOS Developer Onboarding
                
                ### Day 1
                - Set up dev environment: JDK 21, Docker, K8s (minikube), IDE
                - Clone repos: cosmos-ota, cosmos-telemetry, cosmos-fleet-monitor
                - Read: Telemetry Pipeline Architecture (COSMOS-ARCH-001)
                
                ### Week 1
                - Complete Kafka fundamentals training
                - Shadow on-call engineer for one rotation
                - First PR: pick a P3 task from sprint board
                
                ### Key Contacts
                - OTA Team Lead: Thanghavel
                - DevOps: Infra team (#cosmos-infra)
                - Architecture questions: Post in #cosmos-architecture
                """));
    }

    @McpTool(description = "Search Confluence pages by keyword. Searches across page titles and content. Use this to find runbooks, architecture docs, or onboarding guides.")
    public String searchConfluencePages(
            @McpToolParam(description = "Search keyword, e.g. 'rollback', 'telemetry', 'onboarding'") String keyword) {

        List<Map<String, String>> results = pages.stream()
                .filter(p -> p.get("title").toLowerCase().contains(keyword.toLowerCase())
                        || p.get("content").toLowerCase().contains(keyword.toLowerCase()))
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "No Confluence pages found matching '" + keyword + "'.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d page(s) matching '%s':\n\n", results.size(), keyword));
        for (Map<String, String> p : results) {
            sb.append(String.format("[%s] %s\n  Space: %s | Author: %s | Updated: %s\n\n",
                    p.get("id"), p.get("title"), p.get("space"), p.get("author"), p.get("updated")));
        }
        return sb.toString();
    }

    @McpTool(description = "Get the full content of a Confluence page by its ID (e.g., COSMOS-RB-001). Use this to read runbooks during incidents or review architecture docs.")
    public String getConfluencePage(
            @McpToolParam(description = "Confluence page ID, e.g. COSMOS-RB-001") String pageId) {

        return pages.stream()
                .filter(p -> p.get("id").equalsIgnoreCase(pageId))
                .findFirst()
                .map(p -> String.format("# %s\nID: %s | Space: %s | Author: %s | Updated: %s\n\n%s",
                        p.get("title"), p.get("id"), p.get("space"), p.get("author"),
                        p.get("updated"), p.get("content")))
                .orElse("Page " + pageId + " not found.");
    }

    private Map<String, String> page(String id, String title, String space, String author,
                                      String updated, String content) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("title", title);
        p.put("space", space);
        p.put("author", author);
        p.put("updated", updated);
        p.put("content", content);
        return p;
    }
}

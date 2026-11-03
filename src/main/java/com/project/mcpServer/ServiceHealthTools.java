package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ServiceHealthTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, Map<String, Object>> services = new LinkedHashMap<>();

    public ServiceHealthTools() {
        services.put("cosmos-ota-service", Map.of(
                "status", "DEGRADED",
                "uptime", "99.2%",
                "pods", "4/5 Running",
                "cpu", "78%",
                "memory", "82%",
                "kafkaConsumerLag", "120,000 messages",
                "p99Latency", "1200ms",
                "activeConnections", "3,200",
                "lastDeployment", "2026-11-01 06:00",
                "notes", "Consumer lag elevated due to rebalance storm. One pod in CrashLoopBackOff."
        ));

        services.put("cosmos-telemetry-service", Map.of(
                "status", "HEALTHY",
                "uptime", "99.97%",
                "pods", "8/8 Running",
                "cpu", "45%",
                "memory", "60%",
                "kafkaConsumerLag", "250 messages",
                "p99Latency", "180ms",
                "throughput", "~58,000 events/sec",
                "lastDeployment", "2026-10-30 14:00",
                "notes", "Stable after Logstash heap increase. Processing 5TB/day."
        ));

        services.put("cosmos-vehicle-status", Map.of(
                "status", "HEALTHY",
                "uptime", "99.95%",
                "pods", "6/6 Running",
                "cpu", "35%",
                "memory", "55%",
                "dbConnectionPool", "32/80 active",
                "p99Latency", "45ms",
                "cacheHitRate", "94%",
                "lastDeployment", "2026-10-29 08:00",
                "notes", "Stable after index optimization. Circuit breaker: CLOSED."
        ));

        services.put("cosmos-fleet-monitor", Map.of(
                "status", "HEALTHY",
                "uptime", "99.9%",
                "pods", "3/3 Running",
                "cpu", "28%",
                "memory", "42%",
                "connectedVehicles", "1,042,000",
                "p99Latency", "90ms",
                "lastDeployment", "2026-10-28 10:00",
                "notes", "All fleet dashboards operational."
        ));

        services.put("cosmos-device-connectivity", Map.of(
                "status", "DEGRADED",
                "uptime", "99.5%",
                "pods", "5/5 Running",
                "cpu", "62%",
                "memory", "70%",
                "activeMqttConnections", "985,000",
                "failedHandshakes", "200 (cert expiry)",
                "p99Latency", "35ms",
                "lastDeployment", "2026-10-27 12:00",
                "notes", "200 EU devices failing mTLS handshake. Emergency cert rotation in progress."
        ));
    }

    @McpTool(description = "Check the health status of a specific microservice or get an overview of all services. Use this before deployments, during incidents, or for daily health checks.")
    public String checkServiceHealth(
            @McpToolParam(description = "Service name (e.g., cosmos-ota-service, cosmos-telemetry-service) or 'all' for overview") String serviceName) {

        if ("all".equalsIgnoreCase(serviceName)) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== COSMOS Platform Health Dashboard === (%s)\n\n", LocalDateTime.now().format(FMT)));
            for (Map.Entry<String, Map<String, Object>> entry : services.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> info = entry.getValue();
                String statusIcon = "HEALTHY".equals(info.get("status")) ? "OK" : "WARN";
                sb.append(String.format("[%s] %-30s | Uptime: %s | Pods: %s | p99: %s\n",
                        statusIcon, name, info.get("uptime"), info.get("pods"), info.get("p99Latency")));
            }
            long degradedCount = services.values().stream()
                    .filter(s -> "DEGRADED".equals(s.get("status")))
                    .count();
            sb.append(String.format("\nTotal services: %d | Healthy: %d | Degraded: %d",
                    services.size(), services.size() - degradedCount, degradedCount));
            return sb.toString();
        }

        Map<String, Object> info = services.get(serviceName);
        if (info == null) {
            return "Service '" + serviceName + "' not found. Available: " + String.join(", ", services.keySet());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== %s ===\nChecked at: %s\n\n", serviceName, LocalDateTime.now().format(FMT)));
        for (Map.Entry<String, Object> e : info.entrySet()) {
            sb.append(String.format("  %-22s: %s\n", e.getKey(), e.getValue()));
        }
        return sb.toString();
    }

    @McpTool(description = "Get Kafka consumer lag for a specific service or all services. Use this to detect message processing bottlenecks or during incident triage.")
    public String checkKafkaLag(
            @McpToolParam(description = "Service name or 'all'") String serviceName) {

        if ("all".equalsIgnoreCase(serviceName)) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Kafka Consumer Lag Report ===\n\n");
            sb.append(String.format("%-30s | %-20s | %s\n", "Service", "Consumer Group", "Lag"));
            sb.append("-".repeat(75) + "\n");
            sb.append(String.format("%-30s | %-20s | %s\n", "cosmos-ota-service", "ota-cmd-processor", "120,000"));
            sb.append(String.format("%-30s | %-20s | %s\n", "cosmos-telemetry-service", "telemetry-ingest", "250"));
            sb.append(String.format("%-30s | %-20s | %s\n", "cosmos-vehicle-status", "vehicle-events", "80"));
            sb.append(String.format("%-30s | %-20s | %s\n", "cosmos-fleet-monitor", "fleet-aggregator", "500"));
            sb.append("\nALERT: cosmos-ota-service lag is critically high (>100k). Check INC-2001.");
            return sb.toString();
        }

        Map<String, Object> info = services.get(serviceName);
        if (info == null) {
            return "Service '" + serviceName + "' not found.";
        }
        Object lag = info.get("kafkaConsumerLag");
        if (lag == null) {
            return serviceName + " does not have Kafka consumer metrics.";
        }
        return String.format("%s — Kafka Consumer Lag: %s\nChecked at: %s", serviceName, lag, LocalDateTime.now().format(FMT));
    }
}

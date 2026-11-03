package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class HelloTools {

    @McpTool(description = "Greets a person by name. Returns a greeting message.")
    public String sayHello(
            @McpToolParam(description = "Name of the person to greet") String name) {
        return "Hello, " + name + "! This came from a Java MCP server.";
    }

    @McpTool(description = "Returns the current time in an IANA timezone, e.g. Asia/Kolkata. Read-only.")
    public String currentTime(
            @McpToolParam(description = "IANA timezone id") String timezone) {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of(timezone)).toString();
    }
}

# DevOps MCP Server

A **Model Context Protocol (MCP)** server built with Java 21 and Spring Boot AI that exposes engineering workflow tools to AI assistants. Designed as a practical demonstration of how AI can integrate with Jira, Confluence, incident management, service health monitoring, and the **live GitHub API** — all through a single stdio interface.

## Tools

All tools are **read-only by design**. No write operations are exposed.

| Service | Tool | Description |
|---|---|---|
| **GitHub** (live API) | `listRepos` | List repos for a user (max 30, sorted) |
| | `getRepoDetails` | Repo info: languages, stars, forks, last push |
| | `listIssues` | Open/closed issues & PRs (max 30) |
| | `getRecentCommits` | Recent commits with author, message, date (max 30) |
| **Jira** (simulated) | `searchJiraTickets` | Filter tickets by status, priority, assignee |
| | `getJiraTicket` | Full ticket details by key |
| **Confluence** (simulated) | `searchConfluencePages` | Keyword search across docs |
| | `getConfluencePage` | Full page content by ID |
| **Incidents** (simulated) | `getRecentIncidents` | Filter by severity/status |
| | `getIncidentDetails` | Full incident timeline and RCA |
| **Service Health** (simulated) | `checkServiceHealth` | Health dashboard for microservices |
| | `checkKafkaLag` | Kafka consumer lag report |
| **Utilities** | `sayHello` | Greeting (test tool) |
| | `currentTime` | Current time in any timezone |

Simulated data reflects a real OTA/connected-vehicle platform (COSMOS) with realistic Jira tickets, architecture docs, production incidents, and Kafka metrics.

## Security Model

- **Read-only**: No write operations are exposed to the AI. All tools are strictly read-only.
- **Token via environment variable**: The GitHub token is never hardcoded. It is read from `GITHUB_TOKEN` at runtime.
- **Fine-grained token**: Use a GitHub fine-grained personal access token scoped to selected repositories with read-only permissions (Contents, Issues).
- **Audit log**: Every tool invocation is logged to `mcp-audit.log` with timestamp, tool name, parameters (tokens excluded), duration, and success/failure. The audit log is never written to stdout.
- **Output caps**: All list operations cap results at 30 items. Text fields from untrusted sources (issue titles, commit messages) are truncated to 120 characters.
- **Error handling**: 401, 403, 404, and rate-limit errors return clear, actionable messages.
- **No stdout pollution**: Console logging is disabled (`logging.level.root=OFF`). All logs go to `hello-mcp.log`. Stdout is reserved for the MCP stdio transport.

### Prompt Injection Note

Issue titles, PR titles, and commit messages are **untrusted user content** that flows into the AI's context. This server mitigates risk by:
- Being strictly read-only (no write tools)
- Truncating text fields to limit injection surface
- Labeling data sources clearly in tool descriptions

This is a known risk with any MCP server that fetches user-generated content.

## Setup

### Prerequisites
- Java 21+
- Maven 3.9+
- Node.js 18+ (for MCP Inspector)

### Build
```bash
mvn clean package -DskipTests
```

### Run
```bash
# Without GitHub token (simulated tools work, GitHub tools return auth errors)
java -jar target/mcpServer-0.0.1-SNAPSHOT.jar

# With GitHub token (all tools work)
GITHUB_TOKEN=ghp_your_token java -jar target/mcpServer-0.0.1-SNAPSHOT.jar
```

### Test with MCP Inspector
```bash
npx @modelcontextprotocol/inspector java -jar target/mcpServer-0.0.1-SNAPSHOT.jar
```
Open the URL printed in the terminal. Connect, then use the Tools tab to list and invoke tools.

### Connect to Claude Code
```bash
claude mcp add devtools -- java -jar "/path/to/target/mcpServer-0.0.1-SNAPSHOT.jar"
```

## Example Prompts

Once connected to an AI client:
- *"Show me all open P1 Jira tickets assigned to Thanghavel"*
- *"Check Kafka consumer lag across all services"*
- *"Pull up the OTA rollback runbook"*
- *"List open SEV1 incidents and check the health of cosmos-ota-service"*
- *"Show me the last 5 commits on Thanghavel31/mcp-devtools"*

## Tech Stack

- **Java 21** with virtual threads support
- **Spring Boot 4.1** + Spring AI MCP Server
- **WebFlux WebClient** for non-blocking GitHub API calls
- **Jackson** for JSON parsing
- **MCP stdio transport** for AI client communication

## Project Structure

```
src/main/java/com/project/mcpServer/
  McpServerApplication.java    # Spring Boot entry point
  GitHubTools.java             # Live GitHub REST API tools
  JiraTools.java               # Simulated Jira tools
  ConfluenceTools.java         # Simulated Confluence tools
  IncidentTools.java           # Simulated incident management tools
  ServiceHealthTools.java      # Simulated service health & Kafka lag tools
  HelloTools.java              # Sample greeting & time tools
  ToolAuditLog.java            # Audit logger for all tool invocations
```

## License

MIT

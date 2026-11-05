package com.project.mcpServer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Service
public class GitHubTools {

    private static final int MAX_RESULTS = 30;

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolAuditLog auditLog;

    public GitHubTools(@Value("${github.token:}") String token, ToolAuditLog auditLog) {
        this.auditLog = auditLog;
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("User-Agent", "cosmos-devtools-mcp-server");

        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    @McpTool(description = "List GitHub repositories for a user. Returns repo name, description, language, stars, and last updated date. Read-only. Max 30 results.")
    public String listRepos(
            @McpToolParam(description = "GitHub username") String username,
            @McpToolParam(description = "Sort by: updated, stars, or name") String sortBy) {

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            String response = webClient.get()
                    .uri("/users/{username}/repos?sort={sort}&per_page={limit}&direction=desc", username, sortBy, MAX_RESULTS)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode repos = objectMapper.readTree(response);
            if (!repos.isArray() || repos.isEmpty()) {
                success = true;
                return "No public repositories found for user: " + username;
            }

            StringBuilder sb = new StringBuilder();
            int count = Math.min(repos.size(), MAX_RESULTS);
            sb.append(String.format("Repositories for %s (%d results, sorted by %s):\n\n", username, count, sortBy));
            for (int i = 0; i < count; i++) {
                JsonNode repo = repos.get(i);
                sb.append(String.format("  %s/%s\n", username, repo.get("name").asText()));
                sb.append(String.format("    Language: %s | Stars: %d | Forks: %d | Updated: %s\n\n",
                        nodeText(repo, "language", "N/A"),
                        repo.get("stargazers_count").asInt(),
                        repo.get("forks_count").asInt(),
                        repo.get("updated_at").asText()));
            }
            success = true;
            return sb.toString();
        } catch (WebClientResponseException e) {
            return handleApiError(e);
        } catch (Exception e) {
            return "Error fetching repos: " + e.getMessage();
        } finally {
            auditLog.log("listRepos", Map.of("username", username, "sortBy", sortBy),
                    System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Get detailed info about a specific GitHub repository including description, languages, open issues count, default branch, and recent activity. Read-only.")
    public String getRepoDetails(
            @McpToolParam(description = "Repository owner (GitHub username)") String owner,
            @McpToolParam(description = "Repository name") String repoName) {

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            String repoJson = webClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repoName)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode repo = objectMapper.readTree(repoJson);

            String languagesJson = webClient.get()
                    .uri("/repos/{owner}/{repo}/languages", owner, repoName)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Map<String, Integer> languages = objectMapper.readValue(languagesJson, new TypeReference<>() {});

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== %s/%s ===\n\n", owner, repoName));
            sb.append(String.format("  Description : %s\n", nodeText(repo, "description", "No description")));
            sb.append(String.format("  Default Branch: %s\n", repo.get("default_branch").asText()));
            sb.append(String.format("  Stars: %d | Forks: %d | Open Issues: %d\n",
                    repo.get("stargazers_count").asInt(),
                    repo.get("forks_count").asInt(),
                    repo.get("open_issues_count").asInt()));
            sb.append(String.format("  Created: %s | Last Push: %s\n", repo.get("created_at").asText(), repo.get("pushed_at").asText()));
            sb.append(String.format("  Languages: %s\n", languages.isEmpty() ? "N/A" : languages.keySet()));

            success = true;
            return sb.toString();
        } catch (WebClientResponseException e) {
            return handleApiError(e);
        } catch (Exception e) {
            return "Error fetching repo details: " + e.getMessage();
        } finally {
            auditLog.log("getRepoDetails", Map.of("owner", owner, "repo", repoName),
                    System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "List open issues or pull requests for a GitHub repository. Read-only. Max 30 results. Note: issue/PR titles are untrusted user content.")
    public String listIssues(
            @McpToolParam(description = "Repository owner") String owner,
            @McpToolParam(description = "Repository name") String repoName,
            @McpToolParam(description = "State: open, closed, or all") String state) {

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            String response = webClient.get()
                    .uri("/repos/{owner}/{repo}/issues?state={state}&per_page={limit}&sort=updated&direction=desc",
                            owner, repoName, state, MAX_RESULTS)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode issues = objectMapper.readTree(response);
            if (!issues.isArray() || issues.isEmpty()) {
                success = true;
                return String.format("No %s issues found in %s/%s.", state, owner, repoName);
            }

            StringBuilder sb = new StringBuilder();
            int count = Math.min(issues.size(), MAX_RESULTS);
            sb.append(String.format("Issues in %s/%s (state: %s, showing %d):\n\n", owner, repoName, state, count));
            for (int i = 0; i < count; i++) {
                JsonNode issue = issues.get(i);
                boolean isPR = issue.has("pull_request") && !issue.get("pull_request").isNull();
                String type = isPR ? "PR" : "Issue";
                String title = truncate(issue.get("title").asText(), 120);
                sb.append(String.format("  [#%d] [%s] %s\n", issue.get("number").asInt(), type, title));
                sb.append(String.format("    State: %s | Author: %s | Updated: %s\n",
                        issue.get("state").asText(),
                        issue.get("user").get("login").asText(),
                        issue.get("updated_at").asText()));
                if (issue.has("labels") && issue.get("labels").isArray() && !issue.get("labels").isEmpty()) {
                    StringBuilder labels = new StringBuilder();
                    for (JsonNode label : issue.get("labels")) {
                        if (labels.length() > 0) labels.append(", ");
                        labels.append(label.get("name").asText());
                    }
                    sb.append(String.format("    Labels: %s\n", labels));
                }
                sb.append("\n");
            }
            success = true;
            return sb.toString();
        } catch (WebClientResponseException e) {
            return handleApiError(e);
        } catch (Exception e) {
            return "Error fetching issues: " + e.getMessage();
        } finally {
            auditLog.log("listIssues", Map.of("owner", owner, "repo", repoName, "state", state),
                    System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Get recent commits for a GitHub repository. Shows commit message, author, and date. Read-only. Max 30 commits.")
    public String getRecentCommits(
            @McpToolParam(description = "Repository owner") String owner,
            @McpToolParam(description = "Repository name") String repoName,
            @McpToolParam(description = "Number of commits to fetch (max 30)") int count) {

        long start = System.currentTimeMillis();
        boolean success = false;
        int limit = Math.min(Math.max(count, 1), MAX_RESULTS);
        try {
            String response = webClient.get()
                    .uri("/repos/{owner}/{repo}/commits?per_page={count}", owner, repoName, limit)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode commits = objectMapper.readTree(response);
            if (!commits.isArray() || commits.isEmpty()) {
                success = true;
                return String.format("No commits found in %s/%s.", owner, repoName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Recent commits in %s/%s:\n\n", owner, repoName));
            for (JsonNode commit : commits) {
                JsonNode commitData = commit.get("commit");
                String sha = commit.get("sha").asText().substring(0, 7);
                String message = truncate(commitData.get("message").asText().split("\n")[0], 120);
                String author = commitData.get("author").get("name").asText();
                String date = commitData.get("author").get("date").asText();
                sb.append(String.format("  [%s] %s\n    Author: %s | Date: %s\n\n", sha, message, author, date));
            }
            success = true;
            return sb.toString();
        } catch (WebClientResponseException e) {
            return handleApiError(e);
        } catch (Exception e) {
            return "Error fetching commits: " + e.getMessage();
        } finally {
            auditLog.log("getRecentCommits", Map.of("owner", owner, "repo", repoName, "count", String.valueOf(limit)),
                    System.currentTimeMillis() - start, success);
        }
    }

    private String handleApiError(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return switch (status) {
            case 401 -> "Authentication failed. Set GITHUB_TOKEN env variable with a valid token.";
            case 403 -> {
                String remaining = e.getHeaders().getFirst("X-RateLimit-Remaining");
                if ("0".equals(remaining)) {
                    String reset = e.getHeaders().getFirst("X-RateLimit-Reset");
                    yield "GitHub API rate limit exceeded. Resets at epoch " + reset + ". Use a token to get 5,000 req/hr.";
                }
                yield "Access denied (403). Check token permissions.";
            }
            case 404 -> "Not found (404). Verify the owner/repo name, or the resource may be private.";
            default -> "GitHub API error: " + status + " — " + e.getStatusText();
        };
    }

    private String nodeText(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : defaultValue;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}

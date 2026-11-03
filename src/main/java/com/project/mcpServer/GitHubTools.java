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

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubTools(@Value("${github.token:}") String token) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("User-Agent", "cosmos-devtools-mcp-server");

        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    @McpTool(description = "List GitHub repositories for a user. Returns repo name, description, language, stars, and last updated date. Use this to explore a developer's public projects or verify your own repos.")
    public String listRepos(
            @McpToolParam(description = "GitHub username") String username,
            @McpToolParam(description = "Sort by: updated, stars, or name") String sortBy) {

        try {
            String response = webClient.get()
                    .uri("/users/{username}/repos?sort={sort}&per_page=10&direction=desc", username, sortBy)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode repos = objectMapper.readTree(response);
            if (!repos.isArray() || repos.isEmpty()) {
                return "No public repositories found for user: " + username;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Repositories for %s (top 10 by %s):\n\n", username, sortBy));
            for (JsonNode repo : repos) {
                sb.append(String.format("  %s/%s\n", username, repo.get("name").asText()));
                sb.append(String.format("    Description: %s\n", nodeText(repo, "description", "No description")));
                sb.append(String.format("    Language: %s | Stars: %d | Forks: %d\n",
                        nodeText(repo, "language", "N/A"),
                        repo.get("stargazers_count").asInt(),
                        repo.get("forks_count").asInt()));
                sb.append(String.format("    Updated: %s | URL: %s\n\n",
                        repo.get("updated_at").asText(),
                        repo.get("html_url").asText()));
            }
            return sb.toString();
        } catch (WebClientResponseException e) {
            return "GitHub API error: " + e.getStatusCode() + " — " + e.getMessage();
        } catch (Exception e) {
            return "Error fetching repos: " + e.getMessage();
        }
    }

    @McpTool(description = "Get detailed info about a specific GitHub repository including description, languages, open issues count, default branch, and recent activity.")
    public String getRepoDetails(
            @McpToolParam(description = "Repository owner (GitHub username)") String owner,
            @McpToolParam(description = "Repository name") String repoName) {

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
            sb.append(String.format("  URL         : %s\n", repo.get("html_url").asText()));
            sb.append(String.format("  Default Branch: %s\n", repo.get("default_branch").asText()));
            sb.append(String.format("  Stars: %d | Forks: %d | Watchers: %d\n",
                    repo.get("stargazers_count").asInt(),
                    repo.get("forks_count").asInt(),
                    repo.get("watchers_count").asInt()));
            sb.append(String.format("  Open Issues : %d\n", repo.get("open_issues_count").asInt()));
            sb.append(String.format("  Created     : %s\n", repo.get("created_at").asText()));
            sb.append(String.format("  Last Push   : %s\n", repo.get("pushed_at").asText()));
            sb.append(String.format("  Languages   : %s\n", languages.isEmpty() ? "N/A" : languages.keySet()));

            return sb.toString();
        } catch (WebClientResponseException e) {
            return "GitHub API error: " + e.getStatusCode() + " — " + e.getMessage();
        } catch (Exception e) {
            return "Error fetching repo details: " + e.getMessage();
        }
    }

    @McpTool(description = "List open issues or pull requests for a GitHub repository. Use this to check what needs attention in a project.")
    public String listIssues(
            @McpToolParam(description = "Repository owner") String owner,
            @McpToolParam(description = "Repository name") String repoName,
            @McpToolParam(description = "State: open, closed, or all") String state) {

        try {
            String response = webClient.get()
                    .uri("/repos/{owner}/{repo}/issues?state={state}&per_page=10&sort=updated&direction=desc",
                            owner, repoName, state)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode issues = objectMapper.readTree(response);
            if (!issues.isArray() || issues.isEmpty()) {
                return String.format("No %s issues found in %s/%s.", state, owner, repoName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Issues in %s/%s (state: %s):\n\n", owner, repoName, state));
            for (JsonNode issue : issues) {
                boolean isPR = issue.has("pull_request") && !issue.get("pull_request").isNull();
                String type = isPR ? "PR" : "Issue";
                sb.append(String.format("  [#%d] [%s] %s\n", issue.get("number").asInt(), type, issue.get("title").asText()));
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
            return sb.toString();
        } catch (WebClientResponseException e) {
            return "GitHub API error: " + e.getStatusCode() + " — " + e.getMessage();
        } catch (Exception e) {
            return "Error fetching issues: " + e.getMessage();
        }
    }

    @McpTool(description = "Create a new issue in a GitHub repository. Requires a valid GitHub token with repo scope. Use this to log bugs, feature requests, or tasks directly from a conversation.")
    public String createIssue(
            @McpToolParam(description = "Repository owner") String owner,
            @McpToolParam(description = "Repository name") String repoName,
            @McpToolParam(description = "Issue title") String title,
            @McpToolParam(description = "Issue body/description in Markdown") String body) {

        try {
            String payload = objectMapper.writeValueAsString(Map.of("title", title, "body", body));

            String response = webClient.post()
                    .uri("/repos/{owner}/{repo}/issues", owner, repoName)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode issue = objectMapper.readTree(response);
            return String.format("Issue created successfully!\n\n  [#%d] %s\n  URL: %s\n  State: %s\n  Created: %s",
                    issue.get("number").asInt(),
                    issue.get("title").asText(),
                    issue.get("html_url").asText(),
                    issue.get("state").asText(),
                    issue.get("created_at").asText());
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                return "Authentication failed. Set github.token in application.properties or GITHUB_TOKEN env variable with a token that has 'repo' scope.";
            }
            return "GitHub API error: " + e.getStatusCode() + " — " + e.getMessage();
        } catch (Exception e) {
            return "Error creating issue: " + e.getMessage();
        }
    }

    @McpTool(description = "Get recent commits for a GitHub repository. Shows commit message, author, and date. Use this for code review context or to check recent activity.")
    public String getRecentCommits(
            @McpToolParam(description = "Repository owner") String owner,
            @McpToolParam(description = "Repository name") String repoName,
            @McpToolParam(description = "Number of commits to fetch (max 20)") int count) {

        int limit = Math.min(Math.max(count, 1), 20);
        try {
            String response = webClient.get()
                    .uri("/repos/{owner}/{repo}/commits?per_page={count}", owner, repoName, limit)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode commits = objectMapper.readTree(response);
            if (!commits.isArray() || commits.isEmpty()) {
                return String.format("No commits found in %s/%s.", owner, repoName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Recent commits in %s/%s:\n\n", owner, repoName));
            for (JsonNode commit : commits) {
                JsonNode commitData = commit.get("commit");
                String sha = commit.get("sha").asText().substring(0, 7);
                String message = commitData.get("message").asText().split("\n")[0];
                String author = commitData.get("author").get("name").asText();
                String date = commitData.get("author").get("date").asText();
                sb.append(String.format("  [%s] %s\n    Author: %s | Date: %s\n\n", sha, message, author, date));
            }
            return sb.toString();
        } catch (WebClientResponseException e) {
            return "GitHub API error: " + e.getStatusCode() + " — " + e.getMessage();
        } catch (Exception e) {
            return "Error fetching commits: " + e.getMessage();
        }
    }

    private String nodeText(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : defaultValue;
    }
}

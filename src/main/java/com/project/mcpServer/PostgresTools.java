package com.project.mcpServer;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Map;

@Service
public class PostgresTools {

    private static final int MAX_ROWS = 50;
    private static final int MAX_QUERY_LENGTH = 500;

    private final String url;
    private final String user;
    private final String password;
    private final ToolAuditLog auditLog;

    public PostgresTools(
            @Value("${postgres.url}") String url,
            @Value("${postgres.user}") String user,
            @Value("${postgres.password}") String password,
            ToolAuditLog auditLog) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.auditLog = auditLog;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(url, user, password);
        conn.setReadOnly(true);
        conn.setAutoCommit(false);
        return conn;
    }

    @McpTool(description = "List all tables in the COSMOS database with row counts and disk sizes. Read-only. Use this to understand the schema before querying.")
    public String listTables() {
        long start = System.currentTimeMillis();
        boolean success = false;
        String sql = """
            SELECT
                schemaname,
                relname AS table_name,
                n_live_tup AS estimated_rows,
                pg_size_pretty(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(relname))) AS total_size
            FROM pg_stat_user_tables
            ORDER BY n_live_tup DESC
            """;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            StringBuilder sb = new StringBuilder();
            sb.append("=== COSMOS Database Tables ===\n\n");
            sb.append(String.format("  %-25s | %-15s | %s\n", "Table", "Est. Rows", "Size"));
            sb.append("  " + "-".repeat(60) + "\n");
            while (rs.next()) {
                sb.append(String.format("  %-25s | %-15s | %s\n",
                        rs.getString("table_name"),
                        rs.getLong("estimated_rows"),
                        rs.getString("total_size")));
            }
            success = true;
            return sb.toString();
        } catch (SQLException e) {
            return handleDbError(e);
        } finally {
            auditLog.log("listTables", Map.of(), System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Get table statistics including dead tuples, last vacuum/analyze times, and index usage. Read-only. Use this to detect tables needing maintenance or missing indexes.")
    public String getTableStats(
            @McpToolParam(description = "Table name, e.g. vehicle_events") String tableName) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String sql = """
            SELECT
                relname,
                n_live_tup,
                n_dead_tup,
                last_vacuum,
                last_autovacuum,
                last_analyze,
                last_autoanalyze,
                seq_scan,
                seq_tup_read,
                idx_scan,
                idx_tup_fetch
            FROM pg_stat_user_tables
            WHERE relname = ?
            """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    success = true;
                    return "Table '" + tableName + "' not found.";
                }

                long seqScan = rs.getLong("seq_scan");
                long idxScan = rs.getLong("idx_scan");
                long deadTuples = rs.getLong("n_dead_tup");
                long liveTuples = rs.getLong("n_live_tup");
                double deadRatio = liveTuples > 0 ? (deadTuples * 100.0 / liveTuples) : 0;

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== Table Stats: %s ===\n\n", tableName));
                sb.append(String.format("  Live tuples    : %,d\n", liveTuples));
                sb.append(String.format("  Dead tuples    : %,d (%.1f%% of live)\n", deadTuples, deadRatio));
                sb.append(String.format("  Seq scans      : %,d\n", seqScan));
                sb.append(String.format("  Seq tuples read: %,d\n", rs.getLong("seq_tup_read")));
                sb.append(String.format("  Index scans    : %,d\n", idxScan));
                sb.append(String.format("  Index tuples   : %,d\n", rs.getLong("idx_tup_fetch")));
                sb.append(String.format("  Last vacuum    : %s\n", rs.getTimestamp("last_vacuum")));
                sb.append(String.format("  Last autovacuum: %s\n", rs.getTimestamp("last_autovacuum")));
                sb.append(String.format("  Last analyze   : %s\n", rs.getTimestamp("last_analyze")));

                if (seqScan > idxScan * 2 && liveTuples > 1000) {
                    sb.append("\n  WARNING: High sequential scan ratio. Consider adding an index.");
                }
                if (deadRatio > 20) {
                    sb.append("\n  WARNING: High dead tuple ratio. Consider running VACUUM.");
                }

                success = true;
                return sb.toString();
            }
        } catch (SQLException e) {
            return handleDbError(e);
        } finally {
            auditLog.log("getTableStats", Map.of("table", tableName), System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Get current PostgreSQL connection stats: active, idle, and waiting connections. Read-only. Use this to detect connection pool exhaustion.")
    public String getConnectionStats() {
        long start = System.currentTimeMillis();
        boolean success = false;
        String sql = """
            SELECT
                state,
                COUNT(*) AS count,
                COALESCE(usename, 'system') AS username
            FROM pg_stat_activity
            GROUP BY state, usename
            ORDER BY count DESC
            """;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            StringBuilder sb = new StringBuilder();
            sb.append("=== PostgreSQL Connection Stats ===\n\n");
            sb.append(String.format("  %-15s | %-20s | %s\n", "State", "User", "Count"));
            sb.append("  " + "-".repeat(50) + "\n");
            int total = 0;
            while (rs.next()) {
                String state = rs.getString("state");
                int count = rs.getInt("count");
                total += count;
                sb.append(String.format("  %-15s | %-20s | %d\n",
                        state != null ? state : "NULL",
                        rs.getString("username"),
                        count));
            }
            sb.append(String.format("\n  Total connections: %d\n", total));

            // Get max connections
            try (Statement maxStmt = conn.createStatement();
                 ResultSet maxRs = maxStmt.executeQuery("SHOW max_connections")) {
                if (maxRs.next()) {
                    sb.append(String.format("  Max connections : %s\n", maxRs.getString(1)));
                    double usage = (total * 100.0) / Integer.parseInt(maxRs.getString(1));
                    sb.append(String.format("  Usage           : %.1f%%\n", usage));
                    if (usage > 80) {
                        sb.append("\n  ALERT: Connection usage above 80%%. Risk of pool exhaustion.");
                    }
                }
            }

            success = true;
            return sb.toString();
        } catch (SQLException e) {
            return handleDbError(e);
        } finally {
            auditLog.log("getConnectionStats", Map.of(), System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Get slow queries from pg_stat_statements (if available). Shows queries with highest total execution time. Read-only. Use this to find performance bottlenecks.")
    public String getSlowQueries(
            @McpToolParam(description = "Minimum average execution time in milliseconds to filter, e.g. 10") int minAvgMs) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String sql = """
            SELECT
                SUBSTRING(query, 1, 200) AS query_preview,
                calls,
                ROUND((total_exec_time)::NUMERIC, 2) AS total_ms,
                ROUND((mean_exec_time)::NUMERIC, 2) AS avg_ms,
                rows
            FROM pg_stat_statements
            WHERE mean_exec_time > ?
              AND query NOT LIKE '%pg_stat%'
            ORDER BY mean_exec_time DESC
            LIMIT 10
            """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, minAvgMs);
            try (ResultSet rs = stmt.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== Slow Queries (avg > %dms) ===\n\n", minAvgMs));
                boolean hasRows = false;
                while (rs.next()) {
                    hasRows = true;
                    sb.append(String.format("  Query : %s\n", rs.getString("query_preview")));
                    sb.append(String.format("  Calls : %d | Total: %.2fms | Avg: %.2fms | Rows: %d\n\n",
                            rs.getLong("calls"),
                            rs.getDouble("total_ms"),
                            rs.getDouble("avg_ms"),
                            rs.getLong("rows")));
                }
                if (!hasRows) {
                    sb.append("  No queries found above the threshold. System looks healthy.\n");
                }
                success = true;
                return sb.toString();
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("pg_stat_statements")) {
                return "pg_stat_statements extension not available. Start Postgres with shared_preload_libraries=pg_stat_statements.";
            }
            return handleDbError(e);
        } finally {
            auditLog.log("getSlowQueries", Map.of("minAvgMs", String.valueOf(minAvgMs)),
                    System.currentTimeMillis() - start, success);
        }
    }

    @McpTool(description = "Execute a read-only SQL query against the COSMOS database. Only SELECT and EXPLAIN statements are allowed. Max 50 rows returned. Use this to explore data or run EXPLAIN plans.")
    public String runReadOnlyQuery(
            @McpToolParam(description = "SQL SELECT or EXPLAIN query") String query) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String trimmed = query.strip();

        if (trimmed.length() > MAX_QUERY_LENGTH) {
            return "Query too long. Max " + MAX_QUERY_LENGTH + " characters.";
        }

        String upper = trimmed.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("EXPLAIN") && !upper.startsWith("SHOW")) {
            return "Only SELECT, EXPLAIN, and SHOW statements are allowed. This server is read-only.";
        }
        if (upper.contains("INSERT") || upper.contains("UPDATE") || upper.contains("DELETE")
                || upper.contains("DROP") || upper.contains("ALTER") || upper.contains("CREATE")
                || upper.contains("TRUNCATE") || upper.contains("GRANT")) {
            return "Query contains forbidden keywords. Only read-only operations are allowed.";
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(MAX_ROWS);
            try (ResultSet rs = stmt.executeQuery(trimmed)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                StringBuilder sb = new StringBuilder();

                // Header
                for (int c = 1; c <= colCount; c++) {
                    if (c > 1) sb.append(" | ");
                    sb.append(String.format("%-20s", meta.getColumnLabel(c)));
                }
                sb.append("\n" + "-".repeat(Math.min(colCount * 23, 200)) + "\n");

                // Rows
                int rowCount = 0;
                while (rs.next() && rowCount < MAX_ROWS) {
                    for (int c = 1; c <= colCount; c++) {
                        if (c > 1) sb.append(" | ");
                        String val = rs.getString(c);
                        if (val != null && val.length() > 80) val = val.substring(0, 80) + "...";
                        sb.append(String.format("%-20s", val != null ? val : "NULL"));
                    }
                    sb.append("\n");
                    rowCount++;
                }

                sb.append(String.format("\n(%d row%s returned, max %d)", rowCount, rowCount == 1 ? "" : "s", MAX_ROWS));
                success = true;
                return sb.toString();
            }
        } catch (SQLException e) {
            return handleDbError(e);
        } finally {
            String safeQuery = trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
            auditLog.log("runReadOnlyQuery", Map.of("query", safeQuery),
                    System.currentTimeMillis() - start, success);
        }
    }

    private String handleDbError(SQLException e) {
        String msg = e.getMessage();
        if (msg.contains("Connection refused") || msg.contains("connect")) {
            return "Cannot connect to PostgreSQL. Is the container running? Start with: docker compose up -d";
        }
        if (msg.contains("permission denied") || msg.contains("authentication")) {
            return "Database authentication failed. Check POSTGRES_USER and POSTGRES_PASSWORD.";
        }
        return "Database error: " + msg;
    }
}

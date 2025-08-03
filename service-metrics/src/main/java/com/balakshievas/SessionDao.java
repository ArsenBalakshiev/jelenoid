package com.balakshievas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
public final class SessionDao implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Connection conn;

    public SessionDao(String pgUrl, String pgUser, String pgPassword) {
        try {
            conn = DriverManager.getConnection(pgUrl, pgUser, pgPassword);
            initSchema();
        } catch (SQLException e) {
            log.debug("failed to initialize schema", e);
            conn = null;
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                log.info("PostgreSQL connection closed");
            }
        } catch (SQLException e) {
            log.warn("Error while closing PostgreSQL connection", e);
        }
    }


    public void initSchema() throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS sessions (
                  id         UUID PRIMARY KEY,
                  start_time TIMESTAMPTZ NOT NULL,
                  end_time   TIMESTAMPTZ,
                  platform   VARCHAR(64),
                  version    VARCHAR(32),
                  status     VARCHAR(32),
                  ended_by   VARCHAR(64),
                  received_at TIMESTAMPTZ DEFAULT NOW()
                );
                """;
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
    }

    public void save(byte[] jsonBytes) throws Exception {

        if (conn == null) return;

        JsonNode j = MAPPER.readTree(jsonBytes);

        String sql = """
                INSERT INTO sessions(id,start_time,end_time,platform,version,status,ended_by)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET
                    end_time = EXCLUDED.end_time,
                    status   = EXCLUDED.status,
                    ended_by = EXCLUDED.ended_by;
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.fromString(j.path("id").asText()));
            LocalDateTime start = parseDate(j.path("startTime"));
            ps.setTimestamp(2, Timestamp.valueOf(start));

            if (j.hasNonNull("endTime")) {
                LocalDateTime end = parseDate(j.path("endTime"));
                ps.setTimestamp(3, Timestamp.valueOf(end));
            } else {
                ps.setNull(3, Types.TIMESTAMP);
            }
            ps.setString(4, j.path("platform").asText(null));
            ps.setString(5, j.path("version").asText(null));
            ps.setString(6, j.path("status").asText(null));
            ps.setString(7, j.path("endedBy").asText(null));

            ps.executeUpdate();
        }
    }

    private static LocalDateTime parseDate(JsonNode node) {
        if (node.isTextual()) {
            return LocalDateTime.parse(node.asText());
        }
        if (node.isArray() && node.size() >= 7) {
            return LocalDateTime.of(
                    node.get(0).asInt(),
                    node.get(1).asInt(),
                    node.get(2).asInt(),
                    node.get(3).asInt(),
                    node.get(4).asInt(),
                    node.get(5).asInt(),
                    node.get(6).asInt());
        }
        throw new IllegalArgumentException("Unsupported date format: " + node);
    }
}

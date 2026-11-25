package com.pulseops.monitor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V2 database constraints are enforced by PostgreSQL itself.
 *
 * Uses Flyway directly (no Spring context needed — faster, no JPA overhead).
 * Tests actual DB-level rules so application bugs or missing validation layers
 * cannot silently bypass them.
 */
@Testcontainers
class V2ConstraintTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void runMigrations() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private long insertUser(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO users(email, password_hash) VALUES(?, ?) RETURNING id")) {
            ps.setString(1, "test+" + System.nanoTime() + "@example.com");
            ps.setString(2, "$2a$10$dummyhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── CHECK chk_monitors_claim_consistent ─────────────────────────────────

    /**
     * Core invariant: a row with check_status = 'RUNNING' MUST have both
     * claim_token and claimed_until set. This prevents ghost locks where
     * the scheduler would see RUNNING but have no lease to compare against.
     *
     * Attempts all three invalid combinations:
     *   (a) RUNNING + null claim_token + null claimed_until
     *   (b) RUNNING + non-null claim_token + null claimed_until
     *   (c) RUNNING + null claim_token + non-null claimed_until
     */
    @Test
    void insert_runningWithNullClaimFields_violatesConstraint() throws Exception {
        try (Connection conn = openConnection()) {
            long userId = insertUser(conn);

            // (a) Both NULL — the most dangerous ghost lock case
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                        INSERT INTO monitors(user_id, name, url, check_status, claim_token, claimed_until)
                        VALUES(?, 'Test', 'https://example.com', 'RUNNING', NULL, NULL)
                        """)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_monitors_claim_consistent");

            // (b) claim_token set, claimed_until NULL — lease has no expiry, can't reclaim
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                        INSERT INTO monitors(user_id, name, url, check_status, claim_token, claimed_until)
                        VALUES(?, 'Test', 'https://example.com', 'RUNNING',
                               gen_random_uuid(), NULL)
                        """)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_monitors_claim_consistent");

            // (c) claimed_until set, claim_token NULL — can't verify identity of worker
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                        INSERT INTO monitors(user_id, name, url, check_status, claim_token, claimed_until)
                        VALUES(?, 'Test', 'https://example.com', 'RUNNING',
                               NULL, now() + interval '30 seconds')
                        """)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_monitors_claim_consistent");
        }
    }

    /**
     * Counter-test: IDLE rows with NULL claim fields are valid — that is the
     * normal resting state. IDLE + both NULL is explicitly allowed.
     */
    @Test
    void insert_idleWithNullClaimFields_isValid() throws Exception {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     """
                     INSERT INTO monitors(user_id, name, url, check_status, claim_token, claimed_until)
                     VALUES(?, 'Idle monitor', 'https://example.com', 'IDLE', NULL, NULL)
                     """)) {
            ps.setLong(1, insertUser(conn));
            ps.executeUpdate(); // must not throw
        }
    }

    /**
     * Counter-test: RUNNING with BOTH lease fields populated is valid — that is
     * the correct claimed state.
     */
    @Test
    void insert_runningWithBothClaimFields_isValid() throws Exception {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     """
                     INSERT INTO monitors(user_id, name, url, check_status, claim_token, claimed_until)
                     VALUES(?, 'Running monitor', 'https://example.com', 'RUNNING',
                            gen_random_uuid(), now() + interval '30 seconds')
                     """)) {
            ps.setLong(1, insertUser(conn));
            ps.executeUpdate(); // must not throw
        }
    }
}

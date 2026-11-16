package com.docs0.slack;

import com.docs0.crypto.TokenCipher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Slack workspaces docs0 serves: which tenant a workspace (Slack team id) belongs to, and the tenant's bot
 * token. One workspace per tenant and one tenant per workspace. The token is stored encrypted, decrypted only when
 * a Slack client is built, and never logged or returned in an {@link Installation}.
 */
@Service
public class SlackWorkspaces {

    /** A connected workspace. Deliberately carries no token. */
    public record Installation(long tenantId, String teamId, String teamName, String botUserId, String installedBy,
            String scopes, Instant createdAt, Instant updatedAt) {}

    private static final Logger log = LoggerFactory.getLogger(SlackWorkspaces.class);
    private static final String COLUMNS =
            "tenant_id, slack_team_id, team_name, bot_user_id, installed_by, scopes, created_at, updated_at";

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    public SlackWorkspaces(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** The installation of this Slack team. Empty for a workspace docs0 does not know: its events are ignored. */
    public Optional<Installation> byTeamId(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM slack_installation WHERE slack_team_id = :team")
                .param("team", teamId).query((rs, n) -> row(rs)).optional();
    }

    public Optional<Installation> forTenant(long tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM slack_installation WHERE tenant_id = :tenant")
                .param("tenant", tenantId).query((rs, n) -> row(rs)).optional();
    }

    /** The tenant's bot token, decrypted. Empty when the tenant has no workspace. */
    public Optional<String> botToken(long tenantId) {
        Optional<String> stored = jdbc.sql("SELECT encrypted_bot_token FROM slack_installation WHERE tenant_id = :tenant")
                .param("tenant", tenantId).query(String.class).optional();
        return stored.map(cipher::decrypt);
    }

    /**
     * Connects (or reconnects) the tenant to a workspace, replacing its earlier installation. Fails when the
     * workspace already belongs to another tenant: one Slack team can never serve two companies.
     */
    @Transactional
    public Installation register(long tenantId, String teamId, String teamName, String botToken, String botUserId,
            String installedBy, String scopes) {
        if (teamId == null || teamId.isBlank() || botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("A workspace needs a team id and a bot token");
        }
        String encrypted = cipher.encrypt(botToken);
        try {
            Installation installed = jdbc.sql("""
                            INSERT INTO slack_installation (tenant_id, slack_team_id, team_name, encrypted_bot_token,
                                                            bot_user_id, installed_by, scopes)
                            VALUES (:tenant, :team, :name, :token, :bot, :by, :scopes)
                            ON CONFLICT (tenant_id) DO UPDATE SET
                                slack_team_id = EXCLUDED.slack_team_id, team_name = EXCLUDED.team_name,
                                encrypted_bot_token = EXCLUDED.encrypted_bot_token, bot_user_id = EXCLUDED.bot_user_id,
                                installed_by = EXCLUDED.installed_by, scopes = EXCLUDED.scopes, updated_at = clock_timestamp()
                            RETURNING %s
                            """.formatted(COLUMNS))
                    .param("tenant", tenantId).param("team", teamId)
                    .param("name", teamName, java.sql.Types.VARCHAR).param("token", encrypted)
                    .param("bot", botUserId, java.sql.Types.VARCHAR).param("by", installedBy, java.sql.Types.VARCHAR)
                    .param("scopes", scopes, java.sql.Types.VARCHAR)
                    .query((rs, n) -> row(rs)).single();
            jdbc.sql("UPDATE tenant SET slack_team_id = :team WHERE id = :tenant")
                    .param("team", teamId).param("tenant", tenantId).update();
            log.info("Slack workspace registered: tenant={} team={}", tenantId, teamId);
            return installed;
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("That Slack workspace is already connected to another company");
        }
    }

    private static Installation row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Installation(rs.getLong("tenant_id"), rs.getString("slack_team_id"), rs.getString("team_name"),
                rs.getString("bot_user_id"), rs.getString("installed_by"), rs.getString("scopes"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}

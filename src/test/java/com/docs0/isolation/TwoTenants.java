package com.docs0.isolation;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.UsageLog;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.crypto.TokenCipher;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.settings.ChannelSettings;
import com.docs0.settings.TenantSettings;
import com.docs0.slack.ProactiveOffers;
import com.docs0.slack.SlackWorkspaces;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.action.PendingActionRepository;
import com.docs0.action.PendingAction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two companies in one database, with data in every tenant-scoped table, built to be told apart only by who owns
 * it: the same document title, the same keywords, the same Slack channel id ("C1"), thread ts ("100.1") and user
 * id ("U1") exist on both sides. Anything that crosses over shows up as a wrong id or a wrong marker.
 *
 * <p>Tenant A is "smalltech" (the configured local tenant) with marker ALPHA; tenant B is "acme" with marker BETA.
 */
public final class TwoTenants {

    public static final String CHANNEL = "C1";
    public static final String THREAD = "100.1";
    public static final String USER = "U1";
    /** The question both companies have asked, word for word. */
    public static final String QUESTION = "What is the refund policy?";
    /** The word both companies' documents and corrections share. */
    public static final String KEYWORD = "refund";

    /** Everything seeded for one tenant. */
    public record Side(long tenantId, String slug, String marker, String teamId, String botToken, long driveSource,
            long slackSource, long document, long restrictedDocument, long syncRun, long conversation, long interaction,
            long approvedCorrection, long pendingCorrection, long pendingAction, long staleFlagDocument,
            String linearKey) {

        /** Text only this tenant's data contains. */
        public String owns() {
            return marker;
        }
    }

    public final Side a;
    public final Side b;

    private TwoTenants(Side a, Side b) {
        this.a = a;
        this.b = b;
    }

    public Side side(long tenantId) {
        return tenantId == a.tenantId() ? a : b;
    }

    /** The tenant that is not {@code side}. */
    public Side other(Side side) {
        return side == a ? b : a;
    }

    /**
     * Creates tenant B next to the existing tenant A and fills both. The tables are expected to be empty apart
     * from tenant A.
     */
    public static TwoTenants seed(JdbcClient jdbc, long tenantA) {
        long tenantB = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('acme', 'Acme') RETURNING id")
                .query(Long.class).single();
        // Different amounts of spend, so a budget that reads the wrong tenant is noticed.
        Side a = fill(jdbc, tenantA, "smalltech", "ALPHA", "T_ALPHA", "5.00", "1.00", 30);
        Side b = fill(jdbc, tenantB, "acme", "BETA", "T_BETA", "2.00", "0.50", 5);
        return new TwoTenants(a, b);
    }

    private static Side fill(JdbcClient jdbc, long t, String slug, String marker, String teamId, String questionCost,
            String otherCost, int refundDays) {
        SourceRepository sources = new SourceRepository(jdbc);
        DocumentStore documents = new DocumentStore(jdbc);
        long drive = sources.findOrCreate(t, "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        long slack = sources.findOrCreate(t, "SLACK", "Slack history", "RESTRICTED").id();

        // Documents: the same title and wording, a different marker. One is old enough to be stale.
        Instant old = Instant.now().minus(500, ChronoUnit.DAYS);
        documents.upsert(t, drive, new DocumentWrite("refunds", "Refund policy", "text/plain",
                "https://docs.example.com/" + marker + "/refunds", "Priya " + marker, "priya@" + slug + ".example.com",
                old, "Our refund policy: customers get a refund within " + refundDays + " days. " + marker));
        documents.upsertRestricted(t, slack, new DocumentWrite("thread:C9:1.1", "#leads refund exceptions", "text/plain",
                "https://slack.example.com/" + marker, null, null, Instant.now(),
                "Private refund exceptions are decided by the leads. " + marker), Set.of("u1", "u2"));
        long document = documentId(jdbc, t, "refunds");
        long restricted = documentId(jdbc, t, "thread:C9:1.1");

        // Sync log and credentials.
        SyncRunRepository runs = new SyncRunRepository(jdbc);
        long run = runs.start(t, drive);
        runs.finish(t, run, SyncRun.OK, new SyncCounts(2, 0, 0, 0, 0), null);
        String linearKey = "lin_api_key_" + marker;
        new SourceCredentialRepository(jdbc).saveSecret(t, drive, "Account " + marker,
                new TokenCipher(DriveTestSetup.ENCRYPTION_KEY).encrypt("{\"apiKey\":\"" + linearKey + "\"}"));

        // The Slack workspace.
        String botToken = "xoxb-" + marker.toLowerCase() + "-token";
        new SlackWorkspaces(jdbc, new TokenCipher(DriveTestSetup.ENCRYPTION_KEY)).register(t, teamId,
                marker + " workspace", botToken, "UBOT" + marker, "admin", "chat:write");

        // A conversation with the same channel and thread, an answered question that cited the document.
        Conversation conversation = new ConversationRepository(jdbc).findOrCreate(t, CHANNEL, THREAD, false);
        AgentResult result = new AgentResult(Kind.ANSWER, "Refunds take " + refundDays + " days. " + marker,
                Confidence.HIGH, List.of(new Citation(1, document, "Refund policy", "https://docs.example.com/" + marker,
                "customers get a refund")), List.of(), List.of(), 2, 1000, 100, new BigDecimal(questionCost), 10, null);
        InteractionRepository interactions = new InteractionRepository(jdbc);
        long interaction = interactions.insert(t, conversation.id(), USER, QUESTION, result, "200." + t);

        // Reactions, a prompt, corrections.
        FeedbackRepository feedback = new FeedbackRepository(jdbc);
        feedback.record(t, interaction, USER, Rating.DOWN);
        feedback.createPrompt(t, conversation.id(), interaction);
        CorrectionRepository corrections = new CorrectionRepository(jdbc);
        long approved = corrections.insertPending(t, interaction, USER, "Author " + marker, QUESTION,
                "Refunds take a while.", "Refunds are paid in " + refundDays + " days " + marker, false);
        corrections.approve(t, approved, null);
        long pending = corrections.insertPending(t, interaction, USER, "Author " + marker, QUESTION,
                "Refunds take a while.", "Refund pending fact " + marker, false);

        // A draft waiting for confirmation.
        PendingAction draft = new PendingActionRepository(jdbc).insert(t, conversation.id(), USER,
                PendingAction.Kind.LINEAR_ISSUE, Map.of("teamId", "team-" + marker, "title", "Issue " + marker,
                        "description", "d"));

        // Identity cache, settings, usage, proactive offer, stale flag.
        jdbc.sql("""
                        INSERT INTO identity (tenant_id, slack_user_id, email, display_name, is_bot, deleted)
                        VALUES (:t, 'U1', :email, :name, FALSE, FALSE)
                        """)
                .param("t", t).param("email", "u1@" + slug + ".example.com").param("name", "User " + marker).update();
        new ChannelSettings(jdbc).saveProactive(t, List.of(new ChannelSettings.Channel(CHANNEL, "general-" + marker)),
                Set.of(CHANNEL));
        new TenantSettings(jdbc).put(t, TenantSettings.DIGEST_CHANNEL, CHANNEL);
        new TenantSettings(jdbc).put(t, "note", "note " + marker);
        new UsageLog(jdbc).record(t, UsageLog.THEMES, 100, 10, new BigDecimal(otherCost));
        long earlier = interactions.insert(t, conversation.id(), USER, "Earlier refund question?", result, "201." + t);
        new ProactiveOffers(jdbc).claim(t, CHANNEL, "300." + t, earlier);
        jdbc.sql("""
                        INSERT INTO stale_flag (tenant_id, document_id, interaction_id, document_modified_at)
                        VALUES (:t, :doc, :interaction, :modified)
                        """)
                .param("t", t).param("doc", document).param("interaction", interaction)
                .param("modified", java.sql.Timestamp.from(old)).update();
        return new Side(t, slug, marker, teamId, botToken, drive, slack, document, restricted, run, conversation.id(),
                interaction, approved, pending, draft.id(), document, linearKey);
    }

    private static long documentId(JdbcClient jdbc, long tenantId, String externalId) {
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenantId).param("e", externalId).query(Long.class).single();
    }

    /** The tenant-scoped tables, so a test can check that none of them was touched for the other tenant. */
    public static final List<String> TENANT_TABLES = List.of("source", "document", "document_principal", "sync_run",
            "source_credential", "conversation", "interaction", "feedback", "feedback_prompt", "correction",
            "pending_action", "identity", "channel_setting", "tenant_setting", "usage_log", "proactive_offer",
            "stale_flag", "slack_installation");

    /** Row counts of every tenant-scoped table for one tenant. */
    public static Map<String, Long> counts(JdbcClient jdbc, long tenantId) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String table : TENANT_TABLES) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
                    .param("t", tenantId).query(Long.class).single());
        }
        return counts;
    }
}

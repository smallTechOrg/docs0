package com.docs0.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.budget.BudgetService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StatusTest extends IntegrationTest {

    @Autowired
    StatusController controller;

    @Autowired
    StatusService status;

    @Autowired
    BudgetService budget;

    @Test
    void healthIsUpWithNoCredentialsAndAnEmptyDatabase() {
        Map<String, Object> health = controller.health();

        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("tenant")).isEqualTo("smallTech");
        assertThat(health.get("documents")).isEqualTo(0L);
        assertThat(health.get("questions")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        assertThat(components).containsKey("Database");
    }

    @Test
    void snapshotCountsDocumentsQuestionsAndSpendForThisTenantOnly() {
        long other = tenants.ensure("other", "Other Co").id();
        long src = insertSource(tenant.id());
        long otherSrc = insertSource(other);
        insertDocument(tenant.id(), src, "a", false);
        insertDocument(tenant.id(), src, "b", true); // deleted: not counted
        insertDocument(other, otherSrc, "c", false); // other tenant: not counted
        insertInteraction(tenant.id(), "1.25");
        insertInteraction(other, "9.00");

        var snap = status.snapshot(tenant.id());

        assertThat(snap.documents()).isEqualTo(1);
        assertThat(snap.questions()).isEqualTo(1);
        assertThat(snap.spentThisMonth()).isEqualByComparingTo("1.25");
        assertThat(snap.monthlyCap()).isEqualByComparingTo("100");
        assertThat(snap.lastSync()).isEmpty();
    }

    @Test
    void budgetIsExhaustedOnlyOnceSpendReachesTheCap() {
        assertThat(budget.exhausted(tenant.id())).isFalse();
        insertInteraction(tenant.id(), "99.99");
        assertThat(budget.exhausted(tenant.id())).isFalse();
        insertInteraction(tenant.id(), "0.01");
        assertThat(budget.exhausted(tenant.id())).isTrue();
    }

    private long insertSource(long tenantId) {
        return jdbc.sql("INSERT INTO source (tenant_id, kind, name) VALUES (:t, 'GOOGLE_DRIVE', 'Google Drive') RETURNING id")
                .param("t", tenantId).query(Long.class).single();
    }

    private void insertDocument(long tenantId, long sourceId, String externalId, boolean deleted) {
        jdbc.sql("""
                        INSERT INTO document (tenant_id, source_id, external_id, title, body, deleted)
                        VALUES (:t, :s, :e, 'Title', 'Body', :d)
                        """)
                .param("t", tenantId).param("s", sourceId).param("e", externalId).param("d", deleted).update();
    }

    private void insertInteraction(long tenantId, String cost) {
        long conv = jdbc.sql("""
                        INSERT INTO conversation (tenant_id, channel_id, thread_ts) VALUES (:t, 'C1', :ts) RETURNING id
                        """)
                .param("t", tenantId).param("ts", cost + "-" + System.nanoTime()).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO interaction (tenant_id, conversation_id, slack_user_id, question, cost_usd)
                        VALUES (:t, :c, 'U1', 'q', :cost)
                        """)
                .param("t", tenantId).param("c", conv).param("cost", new BigDecimal(cost)).update();
    }
}

package com.docs0;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.SourceRepository;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Documents for tests, written through the real store. */
public final class TestDocs {

    private TestDocs() {}

    /** A shared document (or a restricted one, visible to U1 only); returns its id. */
    public static long document(JdbcClient jdbc, long tenantId, String externalId, String title, String url,
            String ownerName, String ownerEmail, Instant modifiedAt, boolean restricted) {
        long source = new SourceRepository(jdbc).findOrCreate(tenantId, "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        DocumentWrite write = new DocumentWrite(externalId, title, "text/plain", url, ownerName, ownerEmail, modifiedAt,
                "body of " + externalId);
        DocumentStore store = new DocumentStore(jdbc);
        if (restricted) {
            store.upsertRestricted(tenantId, source, write, Set.of("u1"));
        } else {
            store.upsert(tenantId, source, write);
        }
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenantId).param("e", externalId).query(Long.class).single();
    }
}

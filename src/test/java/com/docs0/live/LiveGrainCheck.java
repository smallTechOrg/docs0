package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.grain.GrainClient;
import com.docs0.source.grain.GrainConnection;
import com.docs0.source.grain.GrainConnector;
import com.docs0.source.grain.HttpGrainClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real-credential, read-only check of the Grain connector: tests the token, lists recent
 * recordings, then syncs a short window into the test database (which fetches and renders each
 * recording) and prints counts only. Never prints a title, a transcript or the token.
 *
 * <p>Skipped unless DOCS0_LIVE=1 and DOCS0_GRAIN_TOKEN is set. Run by hand, never as part of the gate.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
@EnabledIfEnvironmentVariable(named = "DOCS0_GRAIN_TOKEN", matches = ".+")
class LiveGrainCheck extends IntegrationTest {

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    @Autowired
    SyncRunRepository runs;

    @Test
    void listsRecentRecordingsAndSyncsThem() throws Exception {
        String token = System.getenv("DOCS0_GRAIN_TOKEN");
        GrainClient client = new HttpGrainClient();

        GrainClient.Account account = client.account(token);
        List<GrainClient.Recording> recent = client.recordings(token, Instant.now().minus(Duration.ofDays(14)));
        System.out.println("GRAIN scope=" + account.scope() + " recordings in 14 days=" + recent.size());

        GrainConnection connection = new GrainConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources,
                credentials, store);
        connection.connect(tenant.id(), account.name(), token);
        GrainConnector connector = new GrainConnector(client, connection, store, runs, tenants, 14);

        long sourceId = connection.source(tenant.id()).orElseThrow().id();
        SyncCounts counts = connector.sync(tenant.id(), sourceId);
        System.out.println("GRAIN sync: " + counts + " documents=" + store.count(tenant.id(), sourceId));
        assertThat(counts.failed()).isZero();
    }
}

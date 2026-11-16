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
import com.docs0.source.linear.HttpLinearClient;
import com.docs0.source.linear.LinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearConnector;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.HttpTrelloClient;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloClient;
import com.docs0.source.trello.TrelloConnection;
import com.docs0.source.trello.TrelloConnector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real-credential, read-only check of the Linear and Trello connectors: lists teams and boards, then
 * syncs the first team and the first board into the test database and prints counts only. It never
 * creates anything in either tool and never prints a title, a body or a secret.
 *
 * <p>Skipped unless DOCS0_LIVE=1. The Linear part also needs DOCS0_LINEAR_API_KEY, the Trello part
 * DOCS0_TRELLO_KEY and DOCS0_TRELLO_TOKEN. Run by hand, never as part of the gate.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
class LiveLinearTrelloCheck extends IntegrationTest {

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    @Autowired
    SyncRunRepository runs;

    private final TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCS0_LINEAR_API_KEY", matches = ".+")
    void linearListsTeamsAndSyncsTheFirstOne() throws Exception {
        String key = System.getenv("DOCS0_LINEAR_API_KEY");
        LinearClient client = new HttpLinearClient();

        LinearClient.Viewer viewer = client.viewer(key);
        List<LinearTeam> teams = client.teams(key);
        System.out.println("LINEAR workspace=" + viewer.workspaceName() + " teams=" + teams.size()
                + " keys=" + teams.stream().map(LinearTeam::key).toList());
        assertThat(teams).isNotEmpty();

        LinearConnection connection = new LinearConnection(cipher, sources, credentials, store);
        connection.connect(tenant.id(), viewer.workspaceName(), key);
        connection.setTeams(tenant.id(), List.of(teams.get(0)));
        LinearConnector connector = new LinearConnector(client, connection, store, runs);

        SyncCounts counts = connector.sync(tenant.id(), connection.source(tenant.id()).orElseThrow().id());
        System.out.println("LINEAR first team " + teams.get(0).key() + " sync: " + counts + " documents="
                + store.count(tenant.id(), connection.source(tenant.id()).orElseThrow().id()));
        assertThat(counts.added()).isPositive();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCS0_TRELLO_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "DOCS0_TRELLO_TOKEN", matches = ".+")
    void trelloListsBoardsAndSyncsTheFirstOne() throws Exception {
        String key = System.getenv("DOCS0_TRELLO_KEY");
        String token = System.getenv("DOCS0_TRELLO_TOKEN");
        TrelloClient client = new HttpTrelloClient();

        TrelloClient.Account account = client.me(key, token);
        List<TrelloBoard> boards = client.boards(key, token);
        System.out.println("TRELLO account=" + account.username() + " boards=" + boards.size());
        assertThat(boards).isNotEmpty();

        TrelloConnection connection = new TrelloConnection(cipher, sources, credentials, store);
        connection.connect(tenant.id(), account.fullName(), key, token);
        connection.setBoards(tenant.id(), List.of(boards.get(0)));
        TrelloConnector connector = new TrelloConnector(client, connection, store, runs);

        SyncCounts counts = connector.sync(tenant.id(), connection.source(tenant.id()).orElseThrow().id());
        System.out.println("TRELLO first board sync: " + counts + " documents="
                + store.count(tenant.id(), connection.source(tenant.id()).orElseThrow().id()));
        assertThat(counts.added()).isPositive();
    }
}

package com.docs0.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.drive.DriveConnection;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.grain.GrainConnection;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.trello.TrelloConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** What is stored for each kind of credential, and that Drive's grant is unaffected by the new column. */
class CredentialStorageTest extends IntegrationTest {

    private static final String PLAIN_KEY = "lin_api_PLAINTEXT_KEY_1";
    private static final String PLAIN_TRELLO_KEY = "trellokeyPLAINTEXT";
    private static final String PLAIN_TRELLO_TOKEN = "trellotokenPLAINTEXT";

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    private final TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);

    private String wholeRow(String kind) {
        return jdbc.sql("""
                        SELECT CAST(c AS text) FROM source_credential c JOIN source s ON s.id = c.source_id
                        WHERE s.kind = :kind
                        """).param("kind", kind).query(String.class).single();
    }

    @Test
    void aLinearKeyIsStoredAsCiphertextOnly() throws Exception {
        LinearConnection linear = new LinearConnection(cipher, sources, credentials, store);

        linear.connect(tenant.id(), "Acme", PLAIN_KEY);

        String row = wholeRow("LINEAR");
        assertThat(row).doesNotContain(PLAIN_KEY).doesNotContain("lin_api").doesNotContain("apiKey");
        assertThat(row).contains("Acme");
        String secret = jdbc.sql("SELECT encrypted_secret FROM source_credential").query(String.class).single();
        assertThat(cipher.decrypt(secret)).contains(PLAIN_KEY);
        assertThat(jdbc.sql("SELECT encrypted_refresh_token FROM source_credential").query(String.class).optional()).isEmpty();
        assertThat(linear.apiKey(tenant.id())).isEqualTo(PLAIN_KEY);
        assertThat(linear.credential(tenant.id()).orElseThrow().toString()).doesNotContain(secret).doesNotContain(PLAIN_KEY);
    }

    @Test
    void aGrainTokenIsStoredAsCiphertextOnly() throws Exception {
        GrainConnection grain = new GrainConnection(cipher, sources, credentials, store);

        grain.connect(tenant.id(), "Acme Workspace", "grain_pat_PLAINTOKEN");

        assertThat(wholeRow("GRAIN")).doesNotContain("grain_pat_PLAINTOKEN").doesNotContain("PLAINTOKEN");
        assertThat(wholeRow("GRAIN")).contains("Acme Workspace");
        assertThat(grain.token(tenant.id())).isEqualTo("grain_pat_PLAINTOKEN");
        assertThat(grain.credential(tenant.id()).orElseThrow().toString()).doesNotContain("PLAINTOKEN");
    }

    @Test
    void aTrelloKeyAndTokenAreStoredAsCiphertextOnly() throws Exception {
        TrelloConnection trello = new TrelloConnection(cipher, sources, credentials, store);

        trello.connect(tenant.id(), "Asha", PLAIN_TRELLO_KEY, PLAIN_TRELLO_TOKEN);

        assertThat(wholeRow("TRELLO")).doesNotContain(PLAIN_TRELLO_KEY).doesNotContain(PLAIN_TRELLO_TOKEN);
        assertThat(trello.credentials(tenant.id()).key()).isEqualTo(PLAIN_TRELLO_KEY);
        assertThat(trello.credentials(tenant.id()).token()).isEqualTo(PLAIN_TRELLO_TOKEN);
        assertThat(trello.credentials(tenant.id()).toString()).doesNotContain(PLAIN_TRELLO_KEY, PLAIN_TRELLO_TOKEN);
    }

    @Test
    void reconnectingReplacesTheKeyAndKeepsTheTeamSelection() throws Exception {
        LinearConnection linear = new LinearConnection(cipher, sources, credentials, store);
        linear.connect(tenant.id(), "Acme", "lin_api_first");
        linear.setTeams(tenant.id(), java.util.List.of(new com.docs0.source.linear.LinearTeam("t1", "ENG", "Engineering")));

        linear.connect(tenant.id(), "Acme 2", "lin_api_second");

        assertThat(linear.apiKey(tenant.id())).isEqualTo("lin_api_second");
        assertThat(linear.credential(tenant.id()).orElseThrow().accountName()).isEqualTo("Acme 2");
        assertThat(linear.teams(tenant.id())).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aKeyEncryptedWithAnotherKeyIsReportedAsNeedingReconnection() {
        LinearConnection other = new LinearConnection(new TokenCipher(
                java.util.Base64.getEncoder().encodeToString("another-32-byte-key-for-testing!".getBytes())),
                sources, credentials, store);
        other.connect(tenant.id(), "Acme", PLAIN_KEY);
        LinearConnection linear = new LinearConnection(cipher, sources, credentials, store);

        assertThatThrownBy(() -> linear.apiKey(tenant.id())).isInstanceOf(SourceAccessException.class)
                .hasMessage("The stored Linear key cannot be read. Reconnect Linear on the Sources page.");
    }

    @Test
    void aGoogleGrantStoredTheOldWayStillLoadsAndDecrypts() throws Exception {
        // A row exactly as V2 wrote it: only the refresh token column is filled.
        Source drive = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED");
        String encrypted = cipher.encrypt("refresh-token-legacy");
        jdbc.sql("""
                        INSERT INTO source_credential (tenant_id, source_id, account_email, encrypted_refresh_token)
                        VALUES (:t, :s, 'asha@example.com', :token)
                        """).param("t", tenant.id()).param("s", drive.id()).param("token", encrypted).update();
        DriveConnection connection = new DriveConnection(DriveTestSetup.props(), cipher, sources, credentials, store);

        SourceCredential credential = connection.credential(tenant.id()).orElseThrow();

        assertThat(credential.accountEmail()).isEqualTo("asha@example.com");
        assertThat(credential.encryptedSecret()).isNull();
        assertThat(cipher.decrypt(credential.encryptedRefreshToken())).isEqualTo("refresh-token-legacy");
    }

    @Test
    void aNewGoogleGrantStillStoresTheRefreshTokenEncrypted() throws Exception {
        DriveConnection connection = new DriveConnection(DriveTestSetup.props(), cipher, sources, credentials, store);

        connection.connect(tenant.id(), "asha@example.com", "refresh-token-new");

        SourceCredential credential = connection.credential(tenant.id()).orElseThrow();
        assertThat(credential.encryptedSecret()).isNull();
        assertThat(credential.encryptedRefreshToken()).doesNotContain("refresh-token-new");
        assertThat(cipher.decrypt(credential.encryptedRefreshToken())).isEqualTo("refresh-token-new");
    }

    @Test
    void aCredentialRowWithNeitherSecretIsRefused() {
        Source source = sources.findOrCreate(tenant.id(), "LINEAR", "Linear", "SHARED");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO source_credential (tenant_id, source_id, account_email)
                        VALUES (:t, :s, 'x')
                        """).param("t", tenant.id()).param("s", source.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTenantCannotStoreASecretForAnotherTenantsSource() {
        Source source = sources.findOrCreate(tenant.id(), "LINEAR", "Linear", "SHARED");
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();

        credentials.saveSecret(other, source.id(), "Acme", cipher.encrypt("{}"));

        assertThat(jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single()).isZero();
        assertThat(credentials.find(other, source.id())).isEmpty();
    }
}

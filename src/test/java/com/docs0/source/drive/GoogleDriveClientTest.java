package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Only the paths that never reach the network. */
class GoogleDriveClientTest extends IntegrationTest {

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    private GoogleDriveClient client(Docs0Properties props, String key) {
        TokenCipher cipher = new TokenCipher(key);
        return new GoogleDriveClient(props, new DriveConnection(props, cipher, sources, credentials, store), cipher);
    }

    private DriveConnection connection() {
        return new DriveConnection(DriveTestSetup.props(), new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources,
                credentials, store);
    }

    @Test
    void everyCallIsRefusedWhileDriveIsNotConnected() {
        GoogleDriveClient client = client(DriveTestSetup.props(), DriveTestSetup.ENCRYPTION_KEY);
        long t = tenant.id();

        assertThatThrownBy(() -> client.listFiles(t, List.of("folder")))
                .isInstanceOf(SourceAccessException.class).hasMessage(GoogleDriveClient.NOT_CONNECTED_MESSAGE);
        assertThatThrownBy(() -> client.childFolders(t, DriveClient.MY_DRIVE))
                .isInstanceOf(SourceAccessException.class);
        assertThatThrownBy(() -> client.sharedWithMe(t)).isInstanceOf(SourceAccessException.class);
        assertThatThrownBy(() -> client.sharedDrives(t)).isInstanceOf(SourceAccessException.class);
        assertThatThrownBy(() -> client.countIndexableFiles(t, "folder", 10))
                .isInstanceOf(SourceAccessException.class);
        assertThatThrownBy(() -> client.fetchContent(t, new DriveFile("f", "F", "text/plain", null, null, null,
                null, null))).isInstanceOf(SourceAccessException.class);
    }

    @Test
    void noFoldersMeansNoFilesWithoutLookingAtTheGrant() throws IOException {
        assertThat(client(DriveTestSetup.props(), DriveTestSetup.ENCRYPTION_KEY).listFiles(tenant.id(), List.of()))
                .isEmpty();
    }

    @Test
    void aGrantFromAnotherTenantIsNotUsed() {
        long other = tenants.ensure("other", "Other Co").id();
        connection().connect(other, "them@example.com", "their-token");

        assertThatThrownBy(() -> client(DriveTestSetup.props(), DriveTestSetup.ENCRYPTION_KEY)
                .listFiles(tenant.id(), List.of("folder")))
                .isInstanceOf(SourceAccessException.class).hasMessage(GoogleDriveClient.NOT_CONNECTED_MESSAGE);
    }

    @Test
    void aGrantEncryptedWithAnotherKeyAsksForAReconnect() {
        connection().connect(tenant.id(), "asha@example.com", "refresh-token");
        String otherKey = java.util.Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> client(DriveTestSetup.props(), otherKey).listFiles(tenant.id(), List.of("folder")))
                .isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("Reconnect Google Drive on the Sources page")
                .hasMessageNotContaining("refresh-token");
    }

    @Test
    void missingOAuthClientSettingsFailTheCallNotTheApp() {
        connection().connect(tenant.id(), "asha@example.com", "refresh-token");
        Docs0Properties noClient = DriveTestSetup.props("", "", DriveTestSetup.ENCRYPTION_KEY);

        assertThatThrownBy(() -> client(noClient, DriveTestSetup.ENCRYPTION_KEY)
                .listFiles(tenant.id(), List.of("folder")))
                .isInstanceOf(SourceAccessException.class).hasMessageContaining("setup is incomplete");
    }

    @Test
    void aRejectedGrantBecomesTheReconnectMessage() {
        IOException unauthorized = new HttpResponseException.Builder(401, "Unauthorized", new HttpHeaders()).build();
        // The shape google-auth reports a refused refresh in; the real one is exercised in
        // GoogleDriveClientHttpTest.
        IOException refreshFailed = new IOException("Error getting access token for user: 400 Bad Request\n"
                + "{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}");

        for (IOException rejected : List.of(unauthorized, refreshFailed, new IOException("wrapped", unauthorized))) {
            assertThat(GoogleDriveClient.translate(rejected))
                    .isInstanceOf(SourceAccessException.class)
                    .hasMessage("Google access was revoked or expired. Reconnect Google Drive on the Sources page.");
        }
    }

    @Test
    void otherFailuresArePassedOnUnchanged() {
        IOException notFound = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders()).build();
        IOException forbidden = new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders()).build();
        IOException network = new IOException("Connection reset");
        SourceAccessException already = new SourceAccessException("as is");

        assertThat(GoogleDriveClient.translate(notFound)).isSameAs(notFound);
        assertThat(GoogleDriveClient.translate(forbidden)).isSameAs(forbidden);
        assertThat(GoogleDriveClient.translate(network)).isSameAs(network);
        assertThat(GoogleDriveClient.translate(already)).isSameAs(already);
    }
}

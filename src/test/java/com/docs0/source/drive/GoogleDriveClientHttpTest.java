package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.Folder;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The real Drive client (Google's API and auth libraries included) against a local
 * stand-in for Google's token endpoint and the Drive v3 API. Proves what is sent and how
 * answers and refusals are handled; it cannot prove how Google itself answers.
 */
class GoogleDriveClientHttpTest extends IntegrationTest {

    private static final String FOLDER = "application/vnd.google-apps.folder";

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    HttpServer google;
    DriveConnection connection;
    GoogleDriveClient client;

    /** Refresh tokens presented at the token endpoint, in order. */
    final List<String> refreshTokens = new ArrayList<>();
    final List<Map<String, String>> tokenForms = new ArrayList<>();
    /** Query parameters of each files.list call. */
    final List<Map<String, String>> fileQueries = new ArrayList<>();
    final List<String> authorizations = new ArrayList<>();
    /** files.list answers by the "q" (plus "|" and the page token, when there is one). */
    final Map<String, String> fileAnswers = new LinkedHashMap<>();
    boolean grantRevoked;
    boolean apiSays401;

    @BeforeEach
    void start() throws IOException {
        google = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        google.createContext("/token", exchange -> {
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            tokenForms.add(form);
            refreshTokens.add(form.get("refresh_token"));
            if (grantRevoked) {
                respond(exchange, 400, "{\"error\": \"invalid_grant\", "
                        + "\"error_description\": \"Token has been expired or revoked.\"}");
            } else {
                respond(exchange, 200, "{\"access_token\": \"at-" + refreshTokens.size()
                        + "\", \"expires_in\": 3599, \"token_type\": \"Bearer\"}");
            }
        });
        google.createContext("/drive/v3/files", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (apiSays401) {
                respond(exchange, 401, "{\"error\": {\"code\": 401, \"message\": \"Invalid Credentials\"}}");
                return;
            }
            Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
            fileQueries.add(query);
            String key = query.get("q") + (query.containsKey("pageToken") ? "|" + query.get("pageToken") : "");
            respond(exchange, 200, fileAnswers.getOrDefault(key, "{\"files\": []}"));
        });
        google.createContext("/drive/v3/drives", exchange -> respond(exchange, 200,
                "{\"drives\": [{\"id\": \"0ACompany\", \"name\": \"Company\"}]}"));
        google.start();
        String base = "http://127.0.0.1:" + google.getAddress().getPort();

        TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
        connection = new DriveConnection(DriveTestSetup.props(), cipher, sources, credentials, store);
        client = new GoogleDriveClient(DriveTestSetup.props(), connection, cipher, base + "/",
                URI.create(base + "/token"));
        connection.connect(tenant.id(), "asha@example.com", "refresh-token-1");
    }

    @AfterEach
    void stop() {
        google.stop(0);
    }

    @Test
    void listsOnlyInsideTheChosenFoldersRecursivelyAcrossPagesWithSharedDriveSupport() throws IOException {
        fileAnswers.put("'top' in parents and trashed = false", """
                {"nextPageToken": "page2", "files": [
                  {"id": "doc1", "name": "Leave policy", "mimeType": "application/vnd.google-apps.document",
                   "modifiedTime": "2026-03-01T09:00:00.123Z", "webViewLink": "https://docs.example/doc1",
                   "owners": [{"displayName": "Asha Rao", "emailAddress": "asha@example.com"}]},
                  {"id": "sub", "name": "Sub", "mimeType": "%s"}]}
                """.formatted(FOLDER));
        fileAnswers.put("'top' in parents and trashed = false|page2", """
                {"files": [{"id": "pdf1", "name": "Handbook.pdf", "mimeType": "application/pdf", "size": "1234"}]}
                """);
        fileAnswers.put("'sub' in parents and trashed = false", """
                {"files": [{"id": "txt1", "name": "notes.txt", "mimeType": "text/plain", "size": "5"},
                           {"id": "doc1", "name": "Leave policy", "mimeType": "application/vnd.google-apps.document"},
                           {"id": "top", "name": "Loop", "mimeType": "%s"}]}
                """.formatted(FOLDER));

        List<DriveFile> files = client.listFiles(tenant.id(), List.of("top"));

        assertThat(files).extracting(DriveFile::id).containsExactly("doc1", "pdf1", "txt1");
        DriveFile doc = files.get(0);
        assertThat(doc.name()).isEqualTo("Leave policy");
        assertThat(doc.modifiedTime()).isEqualTo(java.time.Instant.parse("2026-03-01T09:00:00.123Z"));
        assertThat(doc.webViewLink()).isEqualTo("https://docs.example/doc1");
        assertThat(doc.ownerName()).isEqualTo("Asha Rao");
        assertThat(doc.ownerEmail()).isEqualTo("asha@example.com");
        assertThat(doc.size()).isNull();
        assertThat(files.get(1).size()).isEqualTo(1234L);

        // Every query is scoped to a parent folder: nothing outside the chosen folder is asked for.
        assertThat(fileQueries).hasSize(3);
        assertThat(fileQueries).allSatisfy(q -> {
            assertThat(q.get("q")).matches("'(top|sub)' in parents and trashed = false");
            assertThat(q.get("supportsAllDrives")).isEqualTo("true");
            assertThat(q.get("includeItemsFromAllDrives")).isEqualTo("true");
        });
        // Signed in with the stored refresh token and the OAuth client, then a bearer token on each call.
        assertThat(tokenForms).hasSize(1);
        assertThat(tokenForms.get(0)).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "refresh-token-1")
                .containsEntry("client_id", DriveTestSetup.CLIENT_ID)
                .containsEntry("client_secret", DriveTestSetup.CLIENT_SECRET);
        assertThat(authorizations).containsOnly("Bearer at-1");
    }

    @Test
    void countsIndexableFilesAndStopsAtTheCap() throws IOException {
        fileAnswers.put("'top' in parents and trashed = false", """
                {"files": [
                  {"id": "a", "mimeType": "text/plain", "size": "5"},
                  {"id": "b", "mimeType": "application/vnd.google-apps.spreadsheet"},
                  {"id": "img", "mimeType": "image/png", "size": "5"},
                  {"id": "huge", "mimeType": "application/pdf", "size": "20971521"},
                  {"id": "sub", "mimeType": "%s"}]}
                """.formatted(FOLDER));
        fileAnswers.put("'sub' in parents and trashed = false",
                "{\"files\": [{\"id\": \"c\", \"mimeType\": \"application/pdf\", \"size\": \"9\"}]}");

        assertThat(client.countIndexableFiles(tenant.id(), "top", 2000)).isEqualTo(3);

        fileQueries.clear();
        assertThat(client.countIndexableFiles(tenant.id(), "top", 2)).isEqualTo(2);
        // The cap was reached in the first folder, so the sub-folder was never opened.
        assertThat(fileQueries).hasSize(1);
    }

    @Test
    void browsesChildFoldersSharedFoldersAndSharedDrives() throws IOException {
        String isFolder = "mimeType = '" + FOLDER + "' and trashed = false";
        fileAnswers.put("'root' in parents and " + isFolder,
                "{\"files\": [{\"id\": \"hr\", \"name\": \"HR\"}, {\"id\": \"ops\", \"name\": \"Ops\"}]}");
        fileAnswers.put("sharedWithMe = true and " + isFolder,
                "{\"files\": [{\"id\": \"fin\", \"name\": \"Finance\"}]}");
        fileAnswers.put("'it\\'s' in parents and " + isFolder, "{\"files\": []}");

        assertThat(client.childFolders(tenant.id(), DriveClient.MY_DRIVE))
                .containsExactly(new Folder("hr", "HR"), new Folder("ops", "Ops"));
        assertThat(client.sharedWithMe(tenant.id())).containsExactly(new Folder("fin", "Finance"));
        assertThat(client.sharedDrives(tenant.id())).containsExactly(new Folder("0ACompany", "Company"));
        // A quote in an id cannot break out of the query.
        assertThat(client.childFolders(tenant.id(), "it's")).isEmpty();
        assertThat(fileQueries).extracting(q -> q.get("q")).contains("'it\\'s' in parents and " + isFolder);
        assertThat(fileQueries).allSatisfy(q -> assertThat(q.get("orderBy")).isEqualTo("name"));
    }

    @Test
    void aRevokedGrantBecomesTheReconnectMessageOnEveryKindOfCall() {
        grantRevoked = true;
        String message = "Google access was revoked or expired. Reconnect Google Drive on the Sources page.";

        assertThatThrownBy(() -> client.listFiles(tenant.id(), List.of("top")))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThatThrownBy(() -> client.childFolders(tenant.id(), "root"))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThatThrownBy(() -> client.sharedWithMe(tenant.id()))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThatThrownBy(() -> client.sharedDrives(tenant.id()))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThatThrownBy(() -> client.countIndexableFiles(tenant.id(), "top", 10))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThatThrownBy(() -> client.fetchContent(tenant.id(),
                new DriveFile("f", "F", "text/plain", null, null, null, null, null)))
                .isInstanceOf(SourceAccessException.class).hasMessage(message);
        assertThat(fileQueries).isEmpty();
    }

    @Test
    void a401FromTheDriveApiBecomesTheReconnectMessage() {
        apiSays401 = true;

        assertThatThrownBy(() -> client.listFiles(tenant.id(), List.of("top")))
                .isInstanceOf(SourceAccessException.class).hasMessage(GoogleDriveClient.RECONNECT_MESSAGE);
    }

    @Test
    void reconnectingAndDisconnectingTakeEffectWithoutARestart() throws IOException {
        client.listFiles(tenant.id(), List.of("top"));
        client.childFolders(tenant.id(), "root");
        // One sign-in serves several calls.
        assertThat(refreshTokens).containsExactly("refresh-token-1");

        connection.connect(tenant.id(), "ben@example.com", "refresh-token-2");
        client.listFiles(tenant.id(), List.of("top"));
        assertThat(refreshTokens).containsExactly("refresh-token-1", "refresh-token-2");
        assertThat(authorizations).containsExactly("Bearer at-1", "Bearer at-1", "Bearer at-2");

        connection.disconnect(tenant.id());
        int callsBefore = fileQueries.size();
        assertThatThrownBy(() -> client.listFiles(tenant.id(), List.of("top")))
                .isInstanceOf(SourceAccessException.class).hasMessage(GoogleDriveClient.NOT_CONNECTED_MESSAGE);
        assertThat(fileQueries).hasSize(callsBefore);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}

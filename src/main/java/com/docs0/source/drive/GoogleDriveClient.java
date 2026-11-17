package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.source.Folder;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.DriveList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.User;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Google Drive over the v3 API, signed in as the admin who connected Drive (their stored
 * refresh token, read-only scope). The API client is built on first use and rebuilt when
 * the stored grant changes, so connecting, reconnecting and disconnecting take effect
 * without a restart and nothing here can fail application startup.
 */
@Component
public class GoogleDriveClient implements DriveClient {

    public static final String RECONNECT_MESSAGE =
            "Google access was revoked or expired. Reconnect Google Drive on the Sources page.";
    public static final String NOT_CONNECTED_MESSAGE =
            "Google Drive is not connected. Connect it on the Sources page.";

    private static final String FILE_FIELDS = "nextPageToken, files(id, name, mimeType, modifiedTime, webViewLink, "
            + "size, owners(displayName, emailAddress))";
    private static final String FOLDER_FIELDS = "nextPageToken, files(id, name)";
    private static final String IS_FOLDER = "mimeType = '" + DriveText.FOLDER + "'";
    private static final int PAGE_SIZE = 1000;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    /** The API client built for one stored grant. */
    private record Session(String encryptedRefreshToken, Drive drive) {}

    /** Told about each file found while walking folders; returns false to stop the walk. */
    private interface FileVisitor {
        boolean visit(File file);
    }

    private final Docs0Properties.Google google;
    private final DriveConnection connection;
    private final TokenCipher cipher;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    /** Null in production: Google's own addresses. Tests point these at a local stand-in. */
    private final String driveRootUrl;
    private final URI tokenServerUri;

    @Autowired
    public GoogleDriveClient(Docs0Properties props, DriveConnection connection, TokenCipher cipher) {
        this(props, connection, cipher, null, null);
    }

    GoogleDriveClient(Docs0Properties props, DriveConnection connection, TokenCipher cipher, String driveRootUrl,
                      URI tokenServerUri) {
        this.google = props.google();
        this.connection = connection;
        this.cipher = cipher;
        this.driveRootUrl = driveRootUrl;
        this.tokenServerUri = tokenServerUri;
    }

    @Override
    public List<DriveFile> listFiles(long tenantId, List<String> folderIds) throws IOException {
        if (folderIds.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, DriveFile> files = new LinkedHashMap<>();
            walk(drive(tenantId), folderIds, FILE_FIELDS, f -> {
                files.putIfAbsent(f.getId(), toDriveFile(f));
                return true;
            });
            return new ArrayList<>(files.values());
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public int countIndexableFiles(long tenantId, String folderId, int cap) throws IOException {
        try {
            Set<String> counted = new HashSet<>();
            walk(drive(tenantId), List.of(folderId), "nextPageToken, files(id, mimeType, size)", f -> {
                if (DriveText.indexable(f.getMimeType(), f.getSize())) {
                    counted.add(f.getId());
                }
                return counted.size() < cap;
            });
            return Math.min(counted.size(), cap);
        } catch (IOException e) {
            throw translate(e);
        }
    }

    /**
     * Breadth-first walk of the folders (or shared drives) and everything below them.
     * Sub-folders are followed, not reported.
     */
    private static void walk(Drive drive, List<String> folderIds, String fields, FileVisitor visitor)
            throws IOException {
        Set<String> visited = new HashSet<>(folderIds);
        Deque<String> queue = new ArrayDeque<>(folderIds);
        while (!queue.isEmpty()) {
            String folderId = queue.poll();
            String pageToken = null;
            do {
                FileList page = drive.files().list()
                        .setQ(inParent(folderId) + " and trashed = false")
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setPageSize(PAGE_SIZE)
                        .setFields(fields)
                        .setPageToken(pageToken)
                        .execute();
                for (File f : page.getFiles()) {
                    if (DriveText.FOLDER.equals(f.getMimeType())) {
                        if (visited.add(f.getId())) {
                            queue.add(f.getId());
                        }
                    } else if (!visitor.visit(f)) {
                        return;
                    }
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
        }
    }

    @Override
    public byte[] fetchContent(long tenantId, DriveFile file) throws IOException {
        try {
            Drive drive = drive(tenantId);
            Optional<String> exportAs = DriveText.exportMimeType(file.mimeType());
            try (InputStream in = exportAs.isPresent()
                    ? drive.files().export(file.id(), exportAs.get()).executeMediaAsInputStream()
                    : drive.files().get(file.id()).setSupportsAllDrives(true).executeMediaAsInputStream()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public List<Folder> childFolders(long tenantId, String parentId) throws IOException {
        return listFolders(tenantId, inParent(parentId) + " and " + IS_FOLDER + " and trashed = false");
    }

    @Override
    public List<Folder> sharedWithMe(long tenantId) throws IOException {
        return listFolders(tenantId, "sharedWithMe = true and " + IS_FOLDER + " and trashed = false");
    }

    private List<Folder> listFolders(long tenantId, String query) throws IOException {
        try {
            Drive drive = drive(tenantId);
            List<Folder> folders = new ArrayList<>();
            String pageToken = null;
            do {
                FileList page = drive.files().list()
                        .setQ(query)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setOrderBy("name")
                        .setPageSize(PAGE_SIZE)
                        .setFields(FOLDER_FIELDS)
                        .setPageToken(pageToken)
                        .execute();
                for (File f : page.getFiles()) {
                    folders.add(new Folder(f.getId(), f.getName()));
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
            return folders;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public List<Folder> sharedDrives(long tenantId) throws IOException {
        try {
            Drive drive = drive(tenantId);
            List<Folder> drives = new ArrayList<>();
            String pageToken = null;
            do {
                DriveList page = drive.drives().list()
                        .setPageSize(100)
                        .setFields("nextPageToken, drives(id, name)")
                        .setPageToken(pageToken)
                        .execute();
                for (com.google.api.services.drive.model.Drive d : page.getDrives()) {
                    drives.add(new Folder(d.getId(), d.getName()));
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
            return drives;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    private static String inParent(String folderId) {
        return "'" + folderId.replace("\\", "\\\\").replace("'", "\\'") + "' in parents";
    }

    private static DriveFile toDriveFile(File f) {
        User owner = f.getOwners() == null || f.getOwners().isEmpty() ? null : f.getOwners().get(0);
        return new DriveFile(
                f.getId(),
                f.getName(),
                f.getMimeType(),
                f.getModifiedTime() == null ? null : Instant.ofEpochMilli(f.getModifiedTime().getValue()),
                f.getWebViewLink(),
                f.getSize(),
                owner == null ? null : owner.getDisplayName(),
                owner == null ? null : owner.getEmailAddress());
    }

    /**
     * Google rejecting the stored grant (a failed token refresh with invalid_grant, or a 401
     * from the API) becomes the reconnect message. Everything else is passed on unchanged.
     */
    static IOException translate(IOException e) {
        if (e instanceof SourceAccessException) {
            return e;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpResponseException http && http.getStatusCode() == 401) {
                return new SourceAccessException(RECONNECT_MESSAGE);
            }
            if (t.getMessage() != null && t.getMessage().contains("invalid_grant")) {
                return new SourceAccessException(RECONNECT_MESSAGE);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return e;
    }

    /** The API client for the tenant's current stored grant. */
    private Drive drive(long tenantId) throws IOException {
        Optional<SourceCredential> stored = connection.credential(tenantId);
        if (stored.isEmpty()) {
            sessions.remove(tenantId);
            throw new SourceAccessException(NOT_CONNECTED_MESSAGE);
        }
        String encrypted = stored.get().encryptedRefreshToken();
        Session session = sessions.get(tenantId);
        if (session == null || !session.encryptedRefreshToken().equals(encrypted)) {
            session = new Session(encrypted, build(encrypted));
            sessions.put(tenantId, session);
        }
        return session.drive();
    }

    private Drive build(String encryptedRefreshToken) throws IOException {
        if (google == null || !google.configured() || !cipher.available()) {
            throw new SourceAccessException("Google Drive setup is incomplete. See the Sources page.");
        }
        String refreshToken;
        try {
            refreshToken = cipher.decrypt(encryptedRefreshToken);
        } catch (IllegalArgumentException e) {
            throw new SourceAccessException("The stored Google sign-in cannot be read with the current "
                    + "DOCS0_ENCRYPTION_KEY. Reconnect Google Drive on the Sources page.");
        }
        UserCredentials.Builder user = UserCredentials.newBuilder()
                .setClientId(google.oauthClientId().trim())
                .setClientSecret(google.oauthClientSecret().trim())
                .setRefreshToken(refreshToken);
        if (tokenServerUri != null) {
            user.setTokenServerUri(tokenServerUri);
        }
        UserCredentials credentials = user.build();
        HttpCredentialsAdapter auth = new HttpCredentialsAdapter(credentials);
        HttpRequestInitializer withTimeouts = request -> {
            auth.initialize(request);
            request.setConnectTimeout(CONNECT_TIMEOUT_MS);
            request.setReadTimeout(READ_TIMEOUT_MS);
        };
        Drive.Builder drive = new Drive.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                withTimeouts).setApplicationName("docs0");
        if (driveRootUrl != null) {
            drive.setRootUrl(driveRootUrl);
        }
        return drive.build();
    }
}

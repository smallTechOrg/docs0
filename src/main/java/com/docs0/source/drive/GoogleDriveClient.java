package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.User;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.stereotype.Component;

/**
 * Google Drive over the v3 API with a service account and the read-only scope. The API
 * client is built on first use, so a missing or bad credentials file fails a sync, never
 * application startup.
 */
@Component
public class GoogleDriveClient implements DriveClient {

    private static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String FILE_FIELDS = "nextPageToken, files(id, name, mimeType, modifiedTime, webViewLink, "
            + "size, owners(displayName, emailAddress))";
    private static final int PAGE_SIZE = 1000;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private final Docs0Properties.Drive props;
    private Drive drive;

    public GoogleDriveClient(Docs0Properties props) {
        this.props = props.drive();
    }

    @Override
    public List<DriveFile> listFiles(List<String> folderIds) throws IOException {
        return folderIds.isEmpty() ? listEverything() : listFolders(folderIds);
    }

    private List<DriveFile> listEverything() throws IOException {
        List<DriveFile> files = new ArrayList<>();
        String pageToken = null;
        do {
            FileList page = drive().files().list()
                    .setQ("trashed = false")
                    .setCorpora("allDrives")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(PAGE_SIZE)
                    .setFields(FILE_FIELDS)
                    .setPageToken(pageToken)
                    .execute();
            for (File f : page.getFiles()) {
                if (!DriveText.FOLDER.equals(f.getMimeType())) {
                    files.add(toDriveFile(f));
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return files;
    }

    /** Breadth-first walk of the configured folders. A file reachable twice is returned once. */
    private List<DriveFile> listFolders(List<String> folderIds) throws IOException {
        Map<String, DriveFile> files = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(folderIds);
        Deque<String> queue = new ArrayDeque<>(folderIds);
        while (!queue.isEmpty()) {
            String folderId = queue.poll();
            String pageToken = null;
            do {
                FileList page = drive().files().list()
                        .setQ("'" + folderId.replace("\\", "\\\\").replace("'", "\\'")
                                + "' in parents and trashed = false")
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setPageSize(PAGE_SIZE)
                        .setFields(FILE_FIELDS)
                        .setPageToken(pageToken)
                        .execute();
                for (File f : page.getFiles()) {
                    if (DriveText.FOLDER.equals(f.getMimeType())) {
                        if (visited.add(f.getId())) {
                            queue.add(f.getId());
                        }
                    } else {
                        files.putIfAbsent(f.getId(), toDriveFile(f));
                    }
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
        }
        return new ArrayList<>(files.values());
    }

    @Override
    public byte[] fetchContent(DriveFile file) throws IOException {
        Optional<String> exportAs = DriveText.exportMimeType(file.mimeType());
        try (InputStream in = exportAs.isPresent()
                ? drive().files().export(file.id(), exportAs.get()).executeMediaAsInputStream()
                : drive().files().get(file.id()).setSupportsAllDrives(true).executeMediaAsInputStream()) {
            return in.readAllBytes();
        }
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

    private synchronized Drive drive() throws IOException {
        if (drive == null) {
            GoogleCredentials credentials;
            try (InputStream in = new FileInputStream(props.credentialsFile())) {
                credentials = ServiceAccountCredentials.fromStream(in).createScoped(READ_ONLY_SCOPE);
            }
            HttpCredentialsAdapter auth = new HttpCredentialsAdapter(credentials);
            HttpRequestInitializer withTimeouts = request -> {
                auth.initialize(request);
                request.setConnectTimeout(CONNECT_TIMEOUT_MS);
                request.setReadTimeout(READ_TIMEOUT_MS);
            };
            drive = new Drive.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), withTimeouts)
                    .setApplicationName("docs0")
                    .build();
        }
        return drive;
    }
}

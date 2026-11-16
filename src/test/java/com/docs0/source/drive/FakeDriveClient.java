package com.docs0.source.drive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory Drive. Records which files were fetched. */
class FakeDriveClient implements DriveClient {

    final Map<String, DriveFile> files = new LinkedHashMap<>();
    final Map<String, byte[]> contents = new LinkedHashMap<>();
    final Set<String> failingIds = new HashSet<>();
    final List<String> fetched = new ArrayList<>();
    List<String> lastFolderIds;
    boolean listingFails;

    void put(String id, String name, String mimeType, Instant modifiedTime, String content) {
        put(id, name, mimeType, modifiedTime, content.getBytes(StandardCharsets.UTF_8), null);
    }

    void put(String id, String name, String mimeType, Instant modifiedTime, byte[] content, Long size) {
        files.put(id, new DriveFile(id, name, mimeType, modifiedTime, "https://drive.example/" + id, size,
                "Asha Rao", "asha@example.com"));
        contents.put(id, content);
    }

    void remove(String id) {
        files.remove(id);
        contents.remove(id);
    }

    @Override
    public List<DriveFile> listFiles(List<String> folderIds) throws IOException {
        lastFolderIds = folderIds;
        if (listingFails) {
            throw new IOException("Drive is unreachable");
        }
        return new ArrayList<>(files.values());
    }

    @Override
    public byte[] fetchContent(DriveFile file) throws IOException {
        fetched.add(file.id());
        if (failingIds.contains(file.id())) {
            throw new IOException("boom");
        }
        return contents.get(file.id());
    }
}

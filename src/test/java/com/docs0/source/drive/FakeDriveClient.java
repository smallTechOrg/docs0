package com.docs0.source.drive;

import com.docs0.source.Folder;
import com.docs0.source.SourceAccessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An in-memory Drive: folders in a tree, files inside folders. Records which files were
 * fetched and how often it was called at all.
 */
public class FakeDriveClient implements DriveClient {

    /** Where {@link #put} places files unless told otherwise. */
    public static final String MAIN = "main";
    /** Parent of folders that other people shared with the account. */
    public static final String SHARED_WITH_ME = "shared-with-me";

    public final Map<String, DriveFile> files = new LinkedHashMap<>();
    public final Map<String, byte[]> contents = new LinkedHashMap<>();
    public final Set<String> failingIds = new HashSet<>();
    public final List<String> fetched = new ArrayList<>();
    public List<String> lastFolderIds;
    public boolean listingFails;
    /** Google no longer accepts the grant. */
    public boolean revoked;
    public int calls;
    /** When set, listing files waits here until released. */
    public CountDownLatch holdListing;

    private final Map<String, String> fileParent = new HashMap<>();
    private final Map<String, Folder> folders = new LinkedHashMap<>();
    private final Map<String, String> folderParent = new HashMap<>();
    private final Map<String, Folder> drives = new LinkedHashMap<>();

    public FakeDriveClient() {
        reset();
    }

    public void reset() {
        files.clear();
        contents.clear();
        failingIds.clear();
        fetched.clear();
        fileParent.clear();
        folders.clear();
        folderParent.clear();
        drives.clear();
        lastFolderIds = null;
        listingFails = false;
        revoked = false;
        calls = 0;
        holdListing = null;
        folder(MAIN, "Main", MY_DRIVE);
    }

    public void folder(String id, String name, String parentId) {
        folders.put(id, new Folder(id, name));
        folderParent.put(id, parentId);
    }

    public void sharedDrive(String id, String name) {
        drives.put(id, new Folder(id, name));
    }

    public void put(String id, String name, String mimeType, Instant modifiedTime, String content) {
        put(id, name, mimeType, modifiedTime, content.getBytes(StandardCharsets.UTF_8), null);
    }

    public void put(String id, String name, String mimeType, Instant modifiedTime, byte[] content, Long size) {
        putIn(MAIN, id, name, mimeType, modifiedTime, content, size);
    }

    public void putIn(String folderId, String id, String name, String mimeType, Instant modifiedTime,
                      String content) {
        putIn(folderId, id, name, mimeType, modifiedTime, content.getBytes(StandardCharsets.UTF_8), null);
    }

    public void putIn(String folderId, String id, String name, String mimeType, Instant modifiedTime,
                      byte[] content, Long size) {
        files.put(id, new DriveFile(id, name, mimeType, modifiedTime, "https://drive.example/" + id, size,
                "Asha Rao", "asha@example.com"));
        contents.put(id, content);
        fileParent.put(id, folderId);
    }

    public void remove(String id) {
        files.remove(id);
        contents.remove(id);
        fileParent.remove(id);
    }

    @Override
    public List<DriveFile> listFiles(long tenantId, List<String> folderIds) throws IOException {
        lastFolderIds = folderIds;
        if (folderIds.isEmpty()) {
            return List.of();
        }
        called();
        if (holdListing != null) {
            try {
                holdListing.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (listingFails) {
            throw new IOException("Drive is unreachable");
        }
        return files.values().stream().filter(f -> under(fileParent.get(f.id()), folderIds)).toList();
    }

    @Override
    public byte[] fetchContent(long tenantId, DriveFile file) throws IOException {
        called();
        fetched.add(file.id());
        if (failingIds.contains(file.id())) {
            throw new IOException("boom");
        }
        return contents.get(file.id());
    }

    @Override
    public List<Folder> childFolders(long tenantId, String parentId) throws IOException {
        called();
        return folders.values().stream()
                .filter(f -> parentId.equals(folderParent.get(f.id())))
                .sorted(Comparator.comparing(Folder::name))
                .toList();
    }

    @Override
    public List<Folder> sharedWithMe(long tenantId) throws IOException {
        return childFolders(tenantId, SHARED_WITH_ME);
    }

    @Override
    public List<Folder> sharedDrives(long tenantId) throws IOException {
        called();
        return new ArrayList<>(drives.values());
    }

    @Override
    public int countIndexableFiles(long tenantId, String folderId, int cap) throws IOException {
        called();
        long count = files.values().stream()
                .filter(f -> under(fileParent.get(f.id()), List.of(folderId)))
                .filter(f -> DriveText.indexable(f.mimeType(), f.size()))
                .count();
        return (int) Math.min(count, cap);
    }

    private void called() throws IOException {
        calls++;
        if (revoked) {
            throw new SourceAccessException(GoogleDriveClient.RECONNECT_MESSAGE);
        }
    }

    /** True when the folder is one of the given folders or lies below one. */
    private boolean under(String folderId, List<String> roots) {
        for (String id = folderId; id != null; id = folderParent.get(id)) {
            if (roots.contains(id)) {
                return true;
            }
        }
        return false;
    }
}

package com.docs0.web.admin;

import com.docs0.source.drive.DriveClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How many indexable files each chosen folder holds, for the "check access" list. Counting
 * walks the folder in Drive, so a result is remembered for a short while: the Sources page
 * reloads itself during a sync and must not repeat the walk every few seconds.
 */
@Component
public class FolderCounts {

    /** The walk stops here; the page then shows "2,000+". */
    static final int CAP = 2000;
    private static final Duration KEEP = Duration.ofMinutes(2);

    private record Key(long tenantId, String folderId) {}

    private record Counted(int files, Instant at) {}

    private final DriveClient drive;
    private final Map<Key, Counted> cache = new ConcurrentHashMap<>();

    public FolderCounts(DriveClient drive) {
        this.drive = drive;
    }

    /** For example "12 files", "1 file" or "2,000+ files". */
    public String describe(long tenantId, String folderId) throws IOException {
        Key key = new Key(tenantId, folderId);
        Counted counted = cache.get(key);
        if (counted == null || counted.at().plus(KEEP).isBefore(Instant.now())) {
            counted = new Counted(drive.countIndexableFiles(tenantId, folderId, CAP), Instant.now());
            cache.put(key, counted);
        }
        int files = counted.files();
        if (files >= CAP) {
            return String.format("%,d+ files", CAP);
        }
        return files == 1 ? "1 file" : String.format("%,d files", files);
    }

    /** Forgets everything remembered for the tenant, e.g. after connecting another account. */
    public void forget(long tenantId) {
        cache.keySet().removeIf(key -> key.tenantId() == tenantId);
    }
}

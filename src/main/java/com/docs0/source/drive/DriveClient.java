package com.docs0.source.drive;

import com.docs0.source.Folder;
import java.io.IOException;
import java.util.List;

/**
 * The network edge to Google Drive, acting as the Google account the tenant's admin
 * connected. Tests substitute a fake. Every method throws
 * {@link com.docs0.source.SourceAccessException} when Drive is not connected or Google
 * rejects the stored grant.
 */
public interface DriveClient {

    /** The parent id that means the top of the connected account's My Drive. */
    String MY_DRIVE = "root";

    /**
     * Every non-trashed file under the given folders, recursively. An id may also be a
     * shared drive. Folders themselves are walked, not returned. A file reachable twice is
     * returned once. No folders: no files, and Drive is not called.
     */
    List<DriveFile> listFiles(long tenantId, List<String> folderIds) throws IOException;

    /**
     * The file's content: Google Docs, Sheets and Slides are exported in the format given by
     * {@link DriveText#exportMimeType}, everything else is downloaded as stored.
     */
    byte[] fetchContent(long tenantId, DriveFile file) throws IOException;

    /** The folders directly inside a folder or shared drive, by name. {@link #MY_DRIVE} is the top of My Drive. */
    List<Folder> childFolders(long tenantId, String parentId) throws IOException;

    /** Folders other people have shared with the connected account. */
    List<Folder> sharedWithMe(long tenantId) throws IOException;

    /** Shared drives the connected account is a member of. */
    List<Folder> sharedDrives(long tenantId) throws IOException;

    /**
     * How many files under the folder (recursively) docs0 would index. Stops walking once
     * {@code cap} is reached and returns {@code cap}.
     */
    int countIndexableFiles(long tenantId, String folderId, int cap) throws IOException;
}

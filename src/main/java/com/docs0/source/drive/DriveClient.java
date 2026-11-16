package com.docs0.source.drive;

import java.io.IOException;
import java.util.List;

/** The network edge to Google Drive. Tests substitute a fake. */
public interface DriveClient {

    /**
     * Every non-trashed file visible to the service account, shared drives included. With
     * folder ids, only the files under those folders, recursively. Folders themselves are
     * walked, not returned.
     */
    List<DriveFile> listFiles(List<String> folderIds) throws IOException;

    /**
     * The file's content: Google Docs, Sheets and Slides are exported in the format given by
     * {@link DriveText#exportMimeType}, everything else is downloaded as stored.
     */
    byte[] fetchContent(DriveFile file) throws IOException;
}

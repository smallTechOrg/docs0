package com.docs0.source.drive;

import java.time.Instant;

/** Metadata of one Drive file. {@code size} is null when Drive does not report one (some Google-native files). */
public record DriveFile(
        String id,
        String name,
        String mimeType,
        Instant modifiedTime,
        String webViewLink,
        Long size,
        String ownerName,
        String ownerEmail) {}

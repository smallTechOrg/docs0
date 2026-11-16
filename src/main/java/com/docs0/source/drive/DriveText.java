package com.docs0.source.drive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Which Drive file types are indexed and how their content becomes plain text. */
public final class DriveText {

    public static final String FOLDER = "application/vnd.google-apps.folder";
    public static final String GOOGLE_DOC = "application/vnd.google-apps.document";
    public static final String GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet";
    public static final String GOOGLE_SLIDES = "application/vnd.google-apps.presentation";
    public static final String PDF = "application/pdf";

    /** Files larger than this are skipped. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /**
     * Google-native type -> the format it is exported in. A CSV export of a spreadsheet
     * contains only its first sheet; acceptable for now.
     */
    private static final Map<String, String> EXPORTS = Map.of(
            GOOGLE_DOC, "text/plain",
            GOOGLE_SHEET, "text/csv",
            GOOGLE_SLIDES, "text/plain");

    private static final Set<String> PLAIN_TEXT = Set.of("text/plain", "text/markdown", "text/csv");

    private DriveText() {}

    public static boolean supported(String mimeType) {
        return mimeType != null && (EXPORTS.containsKey(mimeType) || PDF.equals(mimeType)
                || PLAIN_TEXT.contains(mimeType));
    }

    /** True for a file docs0 indexes: a supported type that is not over the size limit. */
    public static boolean indexable(String mimeType, Long size) {
        return supported(mimeType) && (size == null || size <= MAX_BYTES);
    }

    /** The export format for a Google-native type; empty for files that are downloaded as stored. */
    public static Optional<String> exportMimeType(String mimeType) {
        return Optional.ofNullable(EXPORTS.get(mimeType));
    }

    /** Plain text of a supported file, given the bytes {@link DriveClient#fetchContent} returned. */
    public static String extract(String mimeType, byte[] content) throws IOException {
        if (!supported(mimeType)) {
            throw new IllegalArgumentException("Unsupported type: " + mimeType);
        }
        if (PDF.equals(mimeType)) {
            try (PDDocument pdf = Loader.loadPDF(content)) {
                return new PDFTextStripper().getText(pdf);
            }
        }
        String text = new String(content, StandardCharsets.UTF_8);
        // Drive's text exports start with a byte order mark.
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}

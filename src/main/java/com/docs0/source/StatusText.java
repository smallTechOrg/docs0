package com.docs0.source;

/** Text for the status page that the connectors share. */
public final class StatusText {

    private static final int MAX_STATUS_ERROR_CHARS = 120;

    private StatusText() {}

    /** A sync error on one line, at most 120 characters, for the status page. */
    public static String shorten(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        String oneLine = error.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_STATUS_ERROR_CHARS ? oneLine.substring(0, MAX_STATUS_ERROR_CHARS) + "..." : oneLine;
    }
}

package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import java.util.Base64;

/** Settings for tests that need Drive to be connectable. None of these values is a real secret. */
public final class DriveTestSetup {

    /** A valid DOCS0_ENCRYPTION_KEY made of 32 fixed bytes. */
    public static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(
            "docs0-test-key-32-bytes-long!!!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    public static final String CLIENT_ID = "test-client-id.apps.example";
    public static final String CLIENT_SECRET = "test-client-secret";

    private DriveTestSetup() {}

    /** Only the settings Drive code reads. */
    public static Docs0Properties props(String clientId, String clientSecret, String encryptionKey) {
        return new Docs0Properties(null, null, null, null, new Docs0Properties.Drive(15, false),
                new Docs0Properties.Google(clientId, clientSecret), encryptionKey, "http://localhost:8080", "");
    }

    public static Docs0Properties props() {
        return props(CLIENT_ID, CLIENT_SECRET, ENCRYPTION_KEY);
    }
}

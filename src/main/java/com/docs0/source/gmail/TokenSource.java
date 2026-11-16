package com.docs0.source.gmail;

import java.io.IOException;
import java.time.Instant;

/** Where {@link HttpGmailClient} gets the access token to act as one person. A seam for tests. */
public interface TokenSource {

    /** A bearer token and when it stops working. Never logged. */
    record Token(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "Token[expires " + expiresAt + "]";
        }
    }

    /** A token for {@code scope} acting as {@code subject}, from the service account key {@code keyJson}. */
    Token token(String keyJson, String subject, String scope) throws IOException;
}

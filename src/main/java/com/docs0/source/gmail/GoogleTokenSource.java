package com.docs0.source.gmail;

import com.docs0.source.SourceAccessException;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Domain-wide delegation: signs a request with the service account's private key and asks Google
 * for a token that acts as {@code subject}. Google's refusals are turned into plain messages; the
 * raw text of an exception is never passed on (it can quote the request).
 */
@Component
public class GoogleTokenSource implements TokenSource {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenSource.class);

    private final String tokenUri;

    public GoogleTokenSource() {
        this(GmailKey.TOKEN_URI);
    }

    /** For tests that run a token endpoint of their own: production always uses Google's. */
    GoogleTokenSource(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    @Override
    public Token token(String keyJson, String subject, String scope) throws IOException {
        ServiceAccountCredentials credentials;
        try {
            credentials = credentials(keyJson, tokenUri, subject, scope);
        } catch (IOException | RuntimeException e) {
            throw new GmailClient.KeyRejectedException(HttpGmailClient.KEY_REJECTED);
        }
        try {
            AccessToken token = credentials.refreshAccessToken();
            Instant expires = token.getExpirationTime() == null ? Instant.now().plusSeconds(45 * 60)
                    : token.getExpirationTime().toInstant();
            return new Token(token.getTokenValue(), expires);
        } catch (IOException | RuntimeException e) {
            throw refused(subject, e);
        }
    }

    /**
     * The credentials for one subject. The token endpoint is always Google's, whatever the stored key says, so a
     * key can never make the server call another host.
     */
    static ServiceAccountCredentials credentials(String keyJson, String tokenUri, String subject, String scope)
            throws IOException {
        ServiceAccountCredentials credentials = ServiceAccountCredentials
                .fromStream(new ByteArrayInputStream(keyJson.getBytes(StandardCharsets.UTF_8)));
        credentials = credentials.toBuilder().setTokenServerUri(java.net.URI.create(tokenUri)).build();
        return (ServiceAccountCredentials) credentials.createScoped(List.of(scope)).createDelegated(subject);
    }

    /** Turns a failed token request into the message an admin can act on. */
    static IOException refused(String subject, Exception e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null && text.length() < 4000; t = t.getCause() == t ? null : t.getCause()) {
            text.append(' ').append(t.getMessage());
            if (t instanceof com.google.api.client.http.HttpResponseException http) {
                text.append(' ').append(http.getContent()); // Google's error code is in the body
            }
        }
        String all = text.toString().toLowerCase(Locale.ROOT);
        if (all.contains("unauthorized_client")) {
            return new SourceAccessException(HttpGmailClient.delegation(subject));
        }
        if (all.contains("invalid_grant")) {
            if (all.contains("invalid jwt") || all.contains("signature") || all.contains("iat") && all.contains("exp")) {
                return new GmailClient.KeyRejectedException(HttpGmailClient.KEY_REJECTED);
            }
            return new SourceAccessException(HttpGmailClient.cannotImpersonate(subject));
        }
        if (all.contains("invalid_client") || all.contains("invalid_scope") && !all.contains("unauthorized")) {
            return new GmailClient.KeyRejectedException(HttpGmailClient.KEY_REJECTED);
        }
        log.warn("Google would not give a token: {}", e.getClass().getSimpleName());
        return new IOException("Could not get access from Google (" + e.getClass().getSimpleName() + ")");
    }
}

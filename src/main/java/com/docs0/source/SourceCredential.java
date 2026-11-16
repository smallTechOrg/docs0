package com.docs0.source;

import java.time.Instant;

/** The stored grant of one source. The refresh token is encrypted; see TokenCipher. */
public record SourceCredential(
        long tenantId,
        long sourceId,
        String accountEmail,
        String encryptedRefreshToken,
        Instant createdAt) {

    /** Never prints the token, even encrypted. */
    @Override
    public String toString() {
        return "SourceCredential[sourceId=" + sourceId + ", accountEmail=" + accountEmail + "]";
    }
}

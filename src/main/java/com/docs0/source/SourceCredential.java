package com.docs0.source;

import java.time.Instant;

/**
 * The stored secret of one source. Google keeps its refresh token in {@code encryptedRefreshToken};
 * Linear and Trello keep an encrypted JSON secret in {@code encryptedSecret}. Both are encrypted,
 * see TokenCipher. {@code accountEmail} is the name shown for the connected account: an email for
 * Google, the workspace or account name for Linear and Trello.
 */
public record SourceCredential(
        long tenantId,
        long sourceId,
        String accountEmail,
        String encryptedRefreshToken,
        String encryptedSecret,
        Instant createdAt) {

    /** The name shown for the connected account. */
    public String accountName() {
        return accountEmail;
    }

    /** Never prints a secret, even encrypted. */
    @Override
    public String toString() {
        return "SourceCredential[sourceId=" + sourceId + ", accountEmail=" + accountEmail + "]";
    }
}

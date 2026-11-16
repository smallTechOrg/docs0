package com.docs0.source.grain;

import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's link to Grain: the stored access token (encrypted). Reads and writes only the
 * database; never calls Grain.
 */
@Service
public class GrainConnection {

    public static final String KIND = "GRAIN";
    public static final String NAME = "Grain";
    public static final String VISIBILITY = "SHARED";

    private static final Logger log = LoggerFactory.getLogger(GrainConnection.class);
    private static final Gson GSON = new Gson();

    /** What is stored encrypted. */
    private record Secret(String token) {}

    private final TokenCipher cipher;
    private final SourceRepository sources;
    private final SourceCredentialRepository credentials;
    private final DocumentStore documents;

    public GrainConnection(TokenCipher cipher, SourceRepository sources, SourceCredentialRepository credentials,
                           DocumentStore documents) {
        this.cipher = cipher;
        this.sources = sources;
        this.credentials = credentials;
        this.documents = documents;
    }

    /** False when DOCS0_ENCRYPTION_KEY is not set: a token cannot be stored safely. */
    public boolean canStoreSecrets() {
        return cipher.available();
    }

    public Optional<Source> source(long tenantId) {
        return sources.find(tenantId, KIND, NAME);
    }

    /** The stored credential (for the account name), if Grain is connected. */
    public Optional<SourceCredential> credential(long tenantId) {
        return source(tenantId).flatMap(s -> credentials.find(tenantId, s.id()));
    }

    /** The decrypted token. Throws when Grain is not connected or the stored token cannot be read. */
    public String token(long tenantId) throws SourceAccessException {
        SourceCredential credential = credential(tenantId)
                .orElseThrow(() -> new SourceAccessException("Grain is not connected. Connect it on the Sources page."));
        try {
            Secret secret = GSON.fromJson(cipher.decrypt(credential.encryptedSecret()), Secret.class);
            if (secret == null || secret.token() == null || secret.token().isBlank()) {
                throw new IllegalArgumentException("empty");
            }
            return secret.token();
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException | NullPointerException e) {
            throw new SourceAccessException("The stored Grain token cannot be read. Reconnect Grain on the Sources page.");
        }
    }

    /** Stores the token encrypted, replacing any earlier one. */
    @Transactional
    public void connect(long tenantId, String accountName, String token) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        credentials.saveSecret(tenantId, source.id(), accountName, cipher.encrypt(GSON.toJson(new Secret(token))));
        log.info("Grain connected for tenant {} (source {})", tenantId, source.id());
    }

    /** Deletes the token and marks the source's documents deleted. */
    @Transactional
    public void disconnect(long tenantId) {
        Optional<Source> source = source(tenantId);
        if (source.isEmpty()) {
            return;
        }
        credentials.delete(tenantId, source.get().id());
        int removed = documents.markAllDeleted(tenantId, source.get().id());
        log.info("Grain disconnected for tenant {} (source {}), {} documents removed", tenantId, source.get().id(),
                removed);
    }
}

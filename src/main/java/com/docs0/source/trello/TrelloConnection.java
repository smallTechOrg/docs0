package com.docs0.source.trello;

import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's link to Trello: the stored API key and token (encrypted) and the boards chosen for
 * indexing. Reads and writes only the database; never calls Trello.
 */
@Service
public class TrelloConnection {

    public static final String KIND = "TRELLO";
    public static final String NAME = "Trello";
    public static final String VISIBILITY = "SHARED";
    static final String BOARDS = "boards";

    private static final Logger log = LoggerFactory.getLogger(TrelloConnection.class);
    private static final Gson GSON = new Gson();

    /** The key and token Trello calls need. Never printed. */
    public record Credentials(String key, String token) {

        @Override
        public String toString() {
            return "Credentials[hidden]";
        }
    }

    private final TokenCipher cipher;
    private final SourceRepository sources;
    private final SourceCredentialRepository credentials;
    private final DocumentStore documents;

    public TrelloConnection(TokenCipher cipher, SourceRepository sources, SourceCredentialRepository credentials,
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

    /** The stored credential (for the account name), if Trello is connected. */
    public Optional<SourceCredential> credential(long tenantId) {
        return source(tenantId).flatMap(s -> credentials.find(tenantId, s.id()));
    }

    /** The decrypted key and token. Throws when Trello is not connected or the stored secret cannot be read. */
    public Credentials credentials(long tenantId) throws SourceAccessException {
        SourceCredential credential = credential(tenantId)
                .orElseThrow(() -> new SourceAccessException("Trello is not connected. Connect it on the Sources page."));
        try {
            Credentials secret = GSON.fromJson(cipher.decrypt(credential.encryptedSecret()), Credentials.class);
            if (secret == null || secret.key() == null || secret.token() == null || secret.key().isBlank()
                    || secret.token().isBlank()) {
                throw new IllegalArgumentException("empty");
            }
            return secret;
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException | NullPointerException e) {
            throw new SourceAccessException("The stored Trello key cannot be read. Reconnect Trello on the Sources page.");
        }
    }

    /** Stores the key and token encrypted, replacing any earlier ones. The board selection is kept. */
    @Transactional
    public void connect(long tenantId, String accountName, String key, String token) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        credentials.saveSecret(tenantId, source.id(), accountName,
                cipher.encrypt(GSON.toJson(new Credentials(key, token))));
        log.info("Trello connected for tenant {} (source {})", tenantId, source.id());
    }

    /** Deletes the key and token and marks the source's documents deleted. The board selection is kept. */
    @Transactional
    public void disconnect(long tenantId) {
        Optional<Source> source = source(tenantId);
        if (source.isEmpty()) {
            return;
        }
        credentials.delete(tenantId, source.get().id());
        int removed = documents.markAllDeleted(tenantId, source.get().id());
        log.info("Trello disconnected for tenant {} (source {}), {} documents removed", tenantId, source.get().id(),
                removed);
    }

    /** The boards chosen for indexing. */
    public List<TrelloBoard> boards(long tenantId) {
        return source(tenantId)
                .map(s -> GSON.<List<TrelloBoard>>fromJson(sources.configList(tenantId, s.id(), BOARDS),
                        new TypeToken<List<TrelloBoard>>() {}.getType()))
                .orElse(List.of());
    }

    /** Replaces the board selection. */
    public void setBoards(long tenantId, List<TrelloBoard> boards) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.setConfigList(tenantId, source.id(), BOARDS, GSON.toJson(boards));
    }
}

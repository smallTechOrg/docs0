package com.docs0.source.linear;

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
 * A tenant's link to Linear: the stored API key (encrypted) and the teams chosen for indexing.
 * Reads and writes only the database; never calls Linear.
 */
@Service
public class LinearConnection {

    public static final String KIND = "LINEAR";
    public static final String NAME = "Linear";
    public static final String VISIBILITY = "SHARED";
    static final String TEAMS = "teams";

    private static final Logger log = LoggerFactory.getLogger(LinearConnection.class);
    private static final Gson GSON = new Gson();

    /** What is stored encrypted. */
    private record Secret(String apiKey) {}

    private final TokenCipher cipher;
    private final SourceRepository sources;
    private final SourceCredentialRepository credentials;
    private final DocumentStore documents;

    public LinearConnection(TokenCipher cipher, SourceRepository sources, SourceCredentialRepository credentials,
                            DocumentStore documents) {
        this.cipher = cipher;
        this.sources = sources;
        this.credentials = credentials;
        this.documents = documents;
    }

    /** False when DOCS0_ENCRYPTION_KEY is not set: a key cannot be stored safely. */
    public boolean canStoreSecrets() {
        return cipher.available();
    }

    public Optional<Source> source(long tenantId) {
        return sources.find(tenantId, KIND, NAME);
    }

    /** The stored credential (for the account name), if Linear is connected. */
    public Optional<SourceCredential> credential(long tenantId) {
        return source(tenantId).flatMap(s -> credentials.find(tenantId, s.id()));
    }

    /** The decrypted API key. Throws when Linear is not connected or the stored key cannot be read. */
    public String apiKey(long tenantId) throws SourceAccessException {
        SourceCredential credential = credential(tenantId)
                .orElseThrow(() -> new SourceAccessException("Linear is not connected. Connect it on the Sources page."));
        try {
            Secret secret = GSON.fromJson(cipher.decrypt(credential.encryptedSecret()), Secret.class);
            if (secret == null || secret.apiKey() == null || secret.apiKey().isBlank()) {
                throw new IllegalArgumentException("empty");
            }
            return secret.apiKey();
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException | NullPointerException e) {
            throw new SourceAccessException("The stored Linear key cannot be read. Reconnect Linear on the Sources page.");
        }
    }

    /** Stores the key encrypted, replacing any earlier one. The team selection is kept. */
    @Transactional
    public void connect(long tenantId, String workspaceName, String apiKey) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        credentials.saveSecret(tenantId, source.id(), workspaceName, cipher.encrypt(GSON.toJson(new Secret(apiKey))));
        log.info("Linear connected for tenant {} (source {})", tenantId, source.id());
    }

    /** Deletes the key and marks the source's documents deleted. The team selection is kept. */
    @Transactional
    public void disconnect(long tenantId) {
        Optional<Source> source = source(tenantId);
        if (source.isEmpty()) {
            return;
        }
        credentials.delete(tenantId, source.get().id());
        int removed = documents.markAllDeleted(tenantId, source.get().id());
        log.info("Linear disconnected for tenant {} (source {}), {} documents removed", tenantId, source.get().id(),
                removed);
    }

    /** The teams chosen for indexing. */
    public List<LinearTeam> teams(long tenantId) {
        return source(tenantId)
                .map(s -> GSON.<List<LinearTeam>>fromJson(sources.configList(tenantId, s.id(), TEAMS),
                        new TypeToken<List<LinearTeam>>() {}.getType()))
                .orElse(List.of());
    }

    /** Replaces the team selection. */
    public void setTeams(long tenantId, List<LinearTeam> teams) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.setConfigList(tenantId, source.id(), TEAMS, GSON.toJson(teams));
    }
}

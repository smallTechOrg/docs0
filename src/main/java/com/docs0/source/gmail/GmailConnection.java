package com.docs0.source.gmail;

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
 * A tenant's link to Gmail: the stored service account key (encrypted, as pasted) and the chosen
 * mailboxes in {@code source.config}. Reads and writes only the database; never calls Google.
 */
@Service
public class GmailConnection {

    public static final String KIND = "GMAIL";
    public static final String NAME = "Gmail";
    /** The source as a whole; every document carries restricted = true. */
    public static final String VISIBILITY = "SHARED";
    static final String MAILBOXES = "mailboxes";
    static final String ADMIN_EMAIL = "adminEmail";

    private static final Logger log = LoggerFactory.getLogger(GmailConnection.class);
    private static final Gson GSON = new Gson();

    private final TokenCipher cipher;
    private final SourceRepository sources;
    private final SourceCredentialRepository credentials;
    private final DocumentStore documents;

    public GmailConnection(TokenCipher cipher, SourceRepository sources, SourceCredentialRepository credentials,
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

    /** The stored credential (account name = the service account's email), if a key is stored. */
    public Optional<SourceCredential> credential(long tenantId) {
        return source(tenantId).flatMap(s -> credentials.find(tenantId, s.id()));
    }

    /** Who the stored key belongs to (email and numeric client ID); empty when there is no readable key. */
    public Optional<GmailKey> account(long tenantId) {
        try {
            return GmailKey.parse(keyJson(tenantId));
        } catch (SourceAccessException e) {
            return Optional.empty();
        }
    }

    /** The decrypted key file. Throws when nothing is stored or it cannot be read. Never log or show it. */
    public String keyJson(long tenantId) throws SourceAccessException {
        SourceCredential credential = credential(tenantId).orElseThrow(() -> new SourceAccessException(
                "Gmail is not connected. Paste a service account key on the Sources page."));
        try {
            String json = cipher.decrypt(credential.encryptedSecret());
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("empty");
            }
            return json;
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw new SourceAccessException(
                    "The stored service account key cannot be read. Paste the key again on the Sources page.");
        }
    }

    /** Stores the key (already checked with {@link GmailKey#parse}) encrypted, replacing any earlier one. */
    @Transactional
    public void connect(long tenantId, GmailKey account, String keyJson) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        credentials.saveSecret(tenantId, source.id(), account.email(), cipher.encrypt(keyJson));
        log.info("Gmail key stored for tenant {} (source {})", tenantId, source.id());
    }

    /** Deletes the key and the mailbox choice, and marks every mail document deleted. */
    @Transactional
    public void disconnect(long tenantId) {
        Optional<Source> source = source(tenantId);
        if (source.isEmpty()) {
            return;
        }
        long id = source.get().id();
        credentials.delete(tenantId, id);
        sources.setConfigList(tenantId, id, MAILBOXES, "[]");
        sources.setConfigText(tenantId, id, ADMIN_EMAIL, null);
        int removed = documents.markAllDeleted(tenantId, id);
        log.info("Gmail disconnected for tenant {} (source {}), {} documents removed", tenantId, id, removed);
    }

    /** The chosen mailboxes, in the order they were saved. */
    public List<Mailbox> mailboxes(long tenantId) {
        return source(tenantId).map(s -> {
            try {
                List<Mailbox> list = GSON.fromJson(sources.configList(tenantId, s.id(), MAILBOXES),
                        new TypeToken<List<Mailbox>>() {}.getType());
                return list == null ? List.<Mailbox>of() : list.stream().filter(m -> Mailbox.valid(m.email())).toList();
            } catch (JsonParseException e) {
                return List.<Mailbox>of();
            }
        }).orElse(List.of());
    }

    /** Replaces the selection. */
    public void setMailboxes(long tenantId, List<Mailbox> mailboxes) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.setConfigList(tenantId, source.id(), MAILBOXES, GSON.toJson(mailboxes));
    }

    /** The administrator docs0 acts as for the directory import, if one was given. */
    public Optional<String> adminEmail(long tenantId) {
        return source(tenantId).map(s -> sources.configText(tenantId, s.id(), ADMIN_EMAIL)).filter(e -> !e.isBlank());
    }

    public void setAdminEmail(long tenantId, String email) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.setConfigText(tenantId, source.id(), ADMIN_EMAIL, email);
    }
}

package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.Folder;
import com.docs0.source.Source;
import com.docs0.source.SourceCredential;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's link to Google Drive: the stored grant of the admin who connected it and the
 * folders chosen for indexing. Reads and writes only the database; never calls Google.
 */
@Service
public class DriveConnection {

    public static final String KIND = "GOOGLE_DRIVE";
    public static final String NAME = "Google Drive";
    public static final String VISIBILITY = "SHARED";

    private static final Logger log = LoggerFactory.getLogger(DriveConnection.class);

    private final Docs0Properties props;
    private final TokenCipher cipher;
    private final SourceRepository sources;
    private final SourceCredentialRepository credentials;
    private final DocumentStore documents;

    public DriveConnection(Docs0Properties props, TokenCipher cipher, SourceRepository sources,
                           SourceCredentialRepository credentials, DocumentStore documents) {
        this.props = props;
        this.cipher = cipher;
        this.sources = sources;
        this.credentials = credentials;
        this.documents = documents;
    }

    /** The env vars that still have to be set before Drive can be connected; empty when ready. */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        Docs0Properties.Google google = props.google();
        if (google == null || blank(google.oauthClientId())) {
            missing.add("DOCS0_GOOGLE_OAUTH_CLIENT_ID");
        }
        if (google == null || blank(google.oauthClientSecret())) {
            missing.add("DOCS0_GOOGLE_OAUTH_CLIENT_SECRET");
        }
        if (!cipher.available()) {
            missing.add("DOCS0_ENCRYPTION_KEY");
        }
        return missing;
    }

    public boolean setupComplete() {
        return missingSettings().isEmpty();
    }

    /** The tenant's Drive source row, if it exists yet. */
    public Optional<Source> source(long tenantId) {
        return sources.find(tenantId, KIND, NAME);
    }

    /** The stored grant, if Drive is connected. */
    public Optional<SourceCredential> credential(long tenantId) {
        return source(tenantId).flatMap(s -> credentials.find(tenantId, s.id()));
    }

    /** Stores the grant encrypted, replacing any earlier one. The folder selection is kept. */
    @Transactional
    public void connect(long tenantId, String accountEmail, String refreshToken) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        credentials.save(tenantId, source.id(), accountEmail, cipher.encrypt(refreshToken));
        log.info("Google Drive connected for tenant {} (source {})", tenantId, source.id());
    }

    /** Deletes the grant and marks the source's documents deleted. The folder selection is kept. */
    @Transactional
    public void disconnect(long tenantId) {
        Optional<Source> source = source(tenantId);
        if (source.isEmpty()) {
            return;
        }
        credentials.delete(tenantId, source.get().id());
        int removed = documents.markAllDeleted(tenantId, source.get().id());
        log.info("Google Drive disconnected for tenant {} (source {}), {} documents removed", tenantId,
                source.get().id(), removed);
    }

    /** The folders chosen for indexing. */
    public List<Folder> folders(long tenantId) {
        return source(tenantId).map(s -> sources.folders(tenantId, s.id())).orElse(List.of());
    }

    public void addFolder(long tenantId, Folder folder) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.addFolder(tenantId, source.id(), folder);
    }

    public void removeFolder(long tenantId, String folderId) {
        source(tenantId).ifPresent(s -> sources.removeFolder(tenantId, s.id(), folderId));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

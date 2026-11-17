package com.docs0.source.trello;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceCredential;
import com.docs0.source.StatusText;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.trello.TrelloClient.BoardContent;
import com.docs0.source.trello.TrelloClient.BoardList;
import com.docs0.source.trello.TrelloClient.Card;
import com.docs0.web.StatusContributor;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keeps the tenant's Trello cards and boards in step with the boards the admin chose. */
@Component
public class TrelloConnector implements SourceConnector, StatusContributor {

    public static final String KIND = TrelloConnection.KIND;
    public static final String NAME = TrelloConnection.NAME;

    private static final Logger log = LoggerFactory.getLogger(TrelloConnector.class);

    private final TrelloClient client;
    private final TrelloConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;

    public TrelloConnector(TrelloClient client, TrelloConnection connection, DocumentStore documents,
                           SyncRunRepository runs) {
        this.client = client;
        this.connection = connection;
        this.documents = documents;
        this.runs = runs;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String visibility() {
        return TrelloConnection.VISIBILITY;
    }

    /** True when Trello is connected and either boards are chosen or documents remain from an earlier choice. */
    @Override
    public boolean configured(long tenantId) {
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty() || connection.credential(tenantId).isEmpty()) {
            return false;
        }
        return !connection.boards(tenantId).isEmpty() || documents.count(tenantId, source.get().id()) > 0;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        List<TrelloBoard> boards = connection.boards(tenantId);
        Map<String, DocumentWrite> wanted = new LinkedHashMap<>();
        Set<String> unreadable = new HashSet<>();
        int failed = 0;

        if (!boards.isEmpty()) {
            // Every board is read before anything is written or deleted: a rejected token or a
            // board that cannot be read leaves the stored documents as they were.
            TrelloConnection.Credentials credentials = connection.credentials(tenantId);
            for (TrelloBoard chosen : boards) {
                BoardContent board;
                try {
                    board = client.board(credentials.key(), credentials.token(), chosen.id());
                } catch (SourceAccessException e) {
                    throw e;
                } catch (IOException e) {
                    throw new IOException("Could not read the Trello board \"" + chosen.name() + "\" ("
                            + e.getMessage() + "). If the board was deleted, untick it on the Sources page.");
                }
                failed += render(wanted, unreadable, "board:" + board.id(), () -> TrelloRender.board(board));
                Map<String, String> listNames = new HashMap<>();
                for (BoardList list : board.lists()) {
                    listNames.put(list.id(), list.name());
                }
                for (Card card : board.cards()) {
                    failed += render(wanted, unreadable, "card:" + card.id(),
                            () -> TrelloRender.card(card, board.name(), listNames.get(card.listId())));
                }
            }
        }

        Map<String, Instant> stored = documents.modifiedTimes(tenantId, sourceId);
        int added = 0;
        int updated = 0;
        for (DocumentWrite doc : wanted.values()) {
            boolean known = stored.containsKey(doc.externalId());
            if (known && Objects.equals(stored.get(doc.externalId()), doc.modifiedAt())) {
                continue;
            }
            try {
                documents.upsert(tenantId, sourceId, doc);
                if (known) {
                    updated++;
                } else {
                    added++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Trello item {} could not be stored: {}", doc.externalId(), e.getClass().getName());
            }
        }

        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(wanted.keySet());
        gone.removeAll(unreadable);
        int removed = documents.markDeleted(tenantId, sourceId, gone);
        return new SyncCounts(added, updated, removed, 0, failed);
    }

    private int render(Map<String, DocumentWrite> wanted, Set<String> unreadable, String externalId,
                       Supplier<DocumentWrite> render) {
        try {
            DocumentWrite doc = render.get();
            wanted.putIfAbsent(doc.externalId(), doc);
            return 0;
        } catch (RuntimeException e) {
            unreadable.add(externalId);
            log.warn("Trello item {} could not be read: {}", externalId, e.getClass().getName());
            return 1;
        }
    }

    /** Reads only the database; never calls Trello. */
    @Override
    public ComponentStatus status(long tenantId) {
        Optional<SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return ComponentStatus.off(NAME, "not connected — connect it on the Sources page");
        }
        if (connection.boards(tenantId).isEmpty()) {
            return ComponentStatus.off(NAME, "connected, nothing chosen yet");
        }
        long sourceId = credential.get().sourceId();
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, "connected as " + credential.get().accountName()
                    + ", waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + StatusText.shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " documents, sync in progress");
        }
        return ComponentStatus.up(NAME, count + " documents, last sync "
                + run.finishedAt().truncatedTo(ChronoUnit.SECONDS));
    }
}

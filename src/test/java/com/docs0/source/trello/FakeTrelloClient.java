package com.docs0.source.trello;

import com.docs0.source.SourceAccessException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory Trello. Records every call that writes. */
public class FakeTrelloClient implements TrelloClient {

    public record CreateCall(String key, String token, String listId, String name, String desc) {}

    public static final String GOOD_KEY = "trello-key-abc";
    public static final String GOOD_TOKEN = "trello-token-xyz";

    public volatile Account account = new Account("Asha Rao", "asharao");
    public final List<TrelloBoard> boards = new CopyOnWriteArrayList<>(List.of(
            new TrelloBoard("b1", "Roadmap"), new TrelloBoard("b2", "Hiring")));
    public final Map<String, BoardContent> content = new LinkedHashMap<>();
    public final List<CreateCall> creates = new CopyOnWriteArrayList<>();
    public final List<String> boardsRead = new CopyOnWriteArrayList<>();
    public volatile IOException readFailure;
    public volatile IOException createFailure;
    public volatile CreatedCard createResult = new CreatedCard("c-new", "https://trello.com/c/NewCard");
    /** Boards whose content cannot be read. */
    public final List<String> brokenBoards = new CopyOnWriteArrayList<>();
    /** When not null, any other key and token is rejected. */
    public volatile String acceptedKey;
    public volatile String acceptedToken;

    /** Back to the state of a new fake. */
    public void reset() {
        account = new Account("Asha Rao", "asharao");
        boards.clear();
        boards.addAll(List.of(new TrelloBoard("b1", "Roadmap"), new TrelloBoard("b2", "Hiring")));
        content.clear();
        creates.clear();
        boardsRead.clear();
        readFailure = null;
        createFailure = null;
        createResult = new CreatedCard("c-new", "https://trello.com/c/NewCard");
        brokenBoards.clear();
        acceptedKey = null;
        acceptedToken = null;
    }

    private void check(String key, String token) throws IOException {
        if (acceptedKey != null && (!acceptedKey.equals(key) || !acceptedToken.equals(token))) {
            throw new SourceAccessException(HttpTrelloClient.REJECTED);
        }
        if (readFailure != null) {
            throw readFailure;
        }
    }

    @Override
    public Account me(String key, String token) throws IOException {
        check(key, token);
        return account;
    }

    @Override
    public List<TrelloBoard> boards(String key, String token) throws IOException {
        check(key, token);
        return List.copyOf(boards);
    }

    @Override
    public BoardContent board(String key, String token, String boardId) throws IOException {
        check(key, token);
        boardsRead.add(boardId);
        if (brokenBoards.contains(boardId)) {
            throw new IOException("Trello could not find that board or card (HTTP 404)");
        }
        BoardContent board = content.get(boardId);
        if (board == null) {
            throw new IOException("Trello could not find that board or card (HTTP 404)");
        }
        return board;
    }

    @Override
    public List<BoardList> lists(String key, String token, String boardId) throws IOException {
        check(key, token);
        BoardContent board = content.get(boardId);
        return board == null ? List.of() : board.lists();
    }

    @Override
    public int countCards(String key, String token, String boardId, int cap) throws IOException {
        check(key, token);
        BoardContent board = content.get(boardId);
        return board == null ? 0 : Math.min(cap, board.cards().size());
    }

    @Override
    public CreatedCard createCard(String key, String token, String listId, String name, String desc)
            throws IOException {
        creates.add(new CreateCall(key, token, listId, name, desc));
        if (createFailure != null) {
            throw createFailure;
        }
        return createResult;
    }

    public static Card card(String id, String name, String listId, Instant lastActivity) {
        return new Card(id, name, "Description of " + name, listId, Instant.parse("2026-04-10T12:00:00Z"), false,
                lastActivity, "https://trello.com/c/" + id, List.of("urgent"), List.of("Priya Nair"),
                List.of(new Checklist("Launch steps", List.of(new ChecklistItem("Write copy", true),
                        new ChecklistItem("Ship it", false)))),
                new ArrayList<>(List.of(new Comment("Marco Diaz", "Waiting on legal", Instant.parse("2026-03-02T10:00:00Z")))));
    }

    public static BoardContent board(String id, String name, Instant lastActivity, List<Card> cards) {
        return new BoardContent(id, name, "Plans for " + name, "https://trello.com/b/" + id, lastActivity,
                List.of(new BoardList("l-todo", "To do"), new BoardList("l-doing", "Doing")), cards);
    }
}

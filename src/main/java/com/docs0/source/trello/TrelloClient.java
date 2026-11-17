package com.docs0.source.trello;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The network edge to Trello. Every call takes the key and token explicitly, so they can be
 * tested before they are stored. Throws {@link com.docs0.source.SourceAccessException} when
 * Trello rejects them. Reads everything except {@link #createCard}, the only write docs0 makes.
 */
public interface TrelloClient {

    /** The member the token belongs to. */
    record Account(String fullName, String username) {}

    record BoardList(String id, String name) {}

    record Comment(String authorName, String text, Instant date) {}

    record ChecklistItem(String name, boolean complete) {}

    record Checklist(String name, List<ChecklistItem> items) {}

    record Card(
            String id,
            String name,
            String desc,
            String listId,
            Instant due,
            boolean dueComplete,
            Instant lastActivity,
            String shortUrl,
            List<String> labels,
            List<String> members,
            List<Checklist> checklists,
            List<Comment> comments) {}

    /** A board with its open lists and open cards (comments, checklists, labels and members included). */
    record BoardContent(
            String id, String name, String desc, String shortUrl, Instant lastActivity, List<BoardList> lists,
            List<Card> cards) {}

    record CreatedCard(String id, String url) {}

    Account me(String key, String token) throws IOException;

    /** The member's open boards. */
    List<TrelloBoard> boards(String key, String token) throws IOException;

    BoardContent board(String key, String token, String boardId) throws IOException;

    /** The open lists of a board, in board order. */
    List<BoardList> lists(String key, String token, String boardId) throws IOException;

    /** How many open cards the board has; stops counting at {@code cap} and returns {@code cap}. */
    int countCards(String key, String token, String boardId, int cap) throws IOException;

    /** Creates a card at the top of a list. */
    CreatedCard createCard(String key, String token, String listId, String name, String desc) throws IOException;
}

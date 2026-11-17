package com.docs0.source.trello;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.trello.TrelloClient.BoardContent;
import com.docs0.source.trello.TrelloClient.BoardList;
import com.docs0.source.trello.TrelloClient.Card;
import com.docs0.source.trello.TrelloClient.Checklist;
import com.docs0.source.trello.TrelloClient.ChecklistItem;
import com.docs0.source.trello.TrelloClient.Comment;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Turns Trello boards and cards into plain text documents with labelled fields. Pure. */
final class TrelloRender {

    static final String CARD_MIME = "application/vnd.trello.card";
    static final String BOARD_MIME = "application/vnd.trello.board";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private TrelloRender() {}

    static DocumentWrite card(Card card, String boardName, String listName) {
        StringBuilder body = new StringBuilder();
        field(body, "Board", boardName);
        field(body, "List", listName);
        field(body, "Labels", card.labels() == null || card.labels().isEmpty() ? null : String.join(", ", card.labels()));
        field(body, "Members", card.members() == null || card.members().isEmpty() ? null : String.join(", ", card.members()));
        field(body, "Due", card.due() == null ? null : DAY.format(card.due()) + (card.dueComplete() ? " (done)" : ""));
        field(body, "Last activity", day(card.lastActivity()));
        field(body, "Link", card.shortUrl());
        if (card.desc() != null && !card.desc().isBlank()) {
            body.append("\nDescription:\n").append(card.desc().strip()).append('\n');
        }
        if (card.checklists() != null) {
            for (Checklist checklist : card.checklists()) {
                body.append("\nChecklist: ").append(orElse(checklist.name(), "Checklist")).append('\n');
                for (ChecklistItem item : checklist.items()) {
                    body.append(item.complete() ? "✓ " : "☐ ").append(orElse(item.name(), "")).append('\n');
                }
            }
        }
        if (card.comments() != null && !card.comments().isEmpty()) {
            body.append("\nComments (").append(card.comments().size()).append("):\n");
            for (Comment c : card.comments()) {
                body.append("- ").append(orElse(c.authorName(), "Someone"));
                if (c.date() != null) {
                    body.append(" (").append(day(c.date())).append(')');
                }
                body.append(": ").append(orElse(c.text(), "").strip()).append('\n');
            }
        }
        String owner = card.members() == null || card.members().isEmpty() ? null : card.members().get(0);
        if (owner == null && card.comments() != null && !card.comments().isEmpty()) {
            owner = card.comments().get(card.comments().size() - 1).authorName();
        }
        return new DocumentWrite("card:" + card.id(), orElse(card.name(), "(untitled card)"), CARD_MIME,
                card.shortUrl(), owner, null, card.lastActivity(), body.toString());
    }

    static DocumentWrite board(BoardContent board) {
        Map<String, Integer> perList = new LinkedHashMap<>();
        for (BoardList list : board.lists()) {
            perList.put(list.id(), 0);
        }
        for (Card card : board.cards()) {
            perList.merge(card.listId(), 1, Integer::sum);
        }
        StringBuilder body = new StringBuilder();
        field(body, "Link", board.shortUrl());
        field(body, "Last activity", day(board.lastActivity()));
        if (board.desc() != null && !board.desc().isBlank()) {
            body.append("\nDescription:\n").append(board.desc().strip()).append('\n');
        }
        body.append("\nLists (").append(board.lists().size()).append("), with the number of open cards:\n");
        for (BoardList list : board.lists()) {
            int n = perList.getOrDefault(list.id(), 0);
            body.append("- ").append(orElse(list.name(), "(unnamed list)")).append(": ").append(n)
                    .append(n == 1 ? " card" : " cards").append('\n');
        }
        return new DocumentWrite("board:" + board.id(), "Trello board: " + orElse(board.name(), "(unnamed)"),
                BOARD_MIME, board.shortUrl(), null, null, board.lastActivity(), body.toString());
    }

    private static void field(StringBuilder body, String label, String value) {
        if (value != null && !value.isBlank()) {
            body.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String day(Instant instant) {
        return instant == null ? null : DAY.format(instant);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.docs0.source.trello;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.trello.TrelloClient.BoardContent;
import com.docs0.source.trello.TrelloClient.BoardList;
import com.docs0.source.trello.TrelloClient.Card;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrelloRenderTest {

    private static final Instant T = Instant.parse("2026-03-05T08:00:00Z");

    @Test
    void aCardHasLabelledFieldsChecklistsWithTicksAndComments() {
        Card card = FakeTrelloClient.card("c1", "Write launch post", "l-doing", T);

        DocumentWrite doc = TrelloRender.card(card, "Roadmap", "Doing");

        assertThat(doc.externalId()).isEqualTo("card:c1");
        assertThat(doc.title()).isEqualTo("Write launch post");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.trello.card");
        assertThat(doc.url()).isEqualTo("https://trello.com/c/c1");
        assertThat(doc.ownerName()).isEqualTo("Priya Nair");
        assertThat(doc.modifiedAt()).isEqualTo(T);
        assertThat(doc.body()).contains("Board: Roadmap", "List: Doing", "Labels: urgent", "Members: Priya Nair",
                "Due: 2026-04-10", "Description:\nDescription of Write launch post", "Checklist: Launch steps",
                "✓ Write copy", "☐ Ship it", "Comments (1):", "- Marco Diaz (2026-03-02): Waiting on legal");
        String body = doc.body();
        assertThat(body.indexOf("Board:")).isLessThan(body.indexOf("List:"));
        assertThat(body.indexOf("Due:")).isLessThan(body.indexOf("Description:"));
        assertThat(body.indexOf("Description:")).isLessThan(body.indexOf("Checklist:"));
        assertThat(body.indexOf("Checklist:")).isLessThan(body.indexOf("Comments"));
    }

    @Test
    void aBoardListsItsListsWithCardCounts() {
        Card a = FakeTrelloClient.card("c1", "A", "l-doing", T);
        Card b = FakeTrelloClient.card("c2", "B", "l-doing", T);
        Card c = FakeTrelloClient.card("c3", "C", "l-todo", T);
        BoardContent board = FakeTrelloClient.board("b1", "Roadmap", T, List.of(a, b, c));

        DocumentWrite doc = TrelloRender.board(board);

        assertThat(doc.externalId()).isEqualTo("board:b1");
        assertThat(doc.title()).isEqualTo("Trello board: Roadmap");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.trello.board");
        assertThat(doc.url()).isEqualTo("https://trello.com/b/b1");
        assertThat(doc.body()).contains("Description:\nPlans for Roadmap", "Lists (2)", "- To do: 1 card",
                "- Doing: 2 cards");
    }

    @Test
    void anEmptyListIsStillListed() {
        BoardContent board = new BoardContent("b9", "Empty", "", "https://trello.com/b/b9", T,
                List.of(new BoardList("l1", "Ideas")), List.of());

        assertThat(TrelloRender.board(board).body()).contains("- Ideas: 0 cards");
    }
}

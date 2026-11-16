package com.docs0.source.linear;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.linear.LinearClient.Comment;
import com.docs0.source.linear.LinearClient.Issue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearRenderTest {

    private static final Instant T = Instant.parse("2026-03-05T08:00:00Z");

    @Test
    void anIssueIsReadableTextWithLabelledFieldsThenDescriptionThenComments() {
        Issue issue = FakeLinearClient.issue("i1", "ENG-12", "Fix login", T);

        DocumentWrite doc = LinearRender.issue(issue);

        assertThat(doc.externalId()).isEqualTo("issue:i1");
        assertThat(doc.title()).isEqualTo("ENG-12: Fix login");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.linear.issue");
        assertThat(doc.url()).isEqualTo("https://linear.app/acme/issue/ENG-12");
        assertThat(doc.ownerName()).isEqualTo("Priya Nair");
        assertThat(doc.ownerEmail()).isEqualTo("priya@example.com");
        assertThat(doc.modifiedAt()).isEqualTo(T);
        assertThat(doc.body())
                .contains("Status: In Progress", "Assignee: Priya Nair <priya@example.com>", "Project: Launch",
                        "Labels: backend", "Priority: High", "Due: 2026-04-01", "Cycle: Cycle 4",
                        "Description:\nDetails of Fix login", "Comments (1):",
                        "- Marco Diaz (2026-03-02): Looks good to me");
        String body = doc.body();
        assertThat(body.indexOf("Status:")).isLessThan(body.indexOf("Assignee:"));
        assertThat(body.indexOf("Assignee:")).isLessThan(body.indexOf("Project:"));
        assertThat(body.indexOf("Priority:")).isLessThan(body.indexOf("Due:"));
        assertThat(body.indexOf("Due:")).isLessThan(body.indexOf("Description:"));
        assertThat(body.indexOf("Description:")).isLessThan(body.indexOf("Comments (1):"));
    }

    @Test
    void anUnassignedIssueSaysSoAndIsOwnedByItsCreator() {
        Issue base = FakeLinearClient.issue("i2", "ENG-13", "Rotate keys", T);
        Issue issue = new Issue(base.id(), base.identifier(), base.title(), null, "Todo", null, null, null,
                "Marco Diaz", "marco@example.com", List.of(), null, null, null, base.createdAt(), T, base.url(),
                "ENG-3", List.of());

        DocumentWrite doc = LinearRender.issue(issue);

        assertThat(doc.body()).contains("Assignee: Unassigned", "Priority: No priority", "Parent: ENG-3")
                .doesNotContain("Description:", "Comments", "Labels:");
        assertThat(doc.ownerName()).isEqualTo("Marco Diaz");
        assertThat(doc.ownerEmail()).isEqualTo("marco@example.com");
    }

    @Test
    void aProjectShowsStatusLeadAndTargetDate() {
        DocumentWrite doc = LinearRender.project(FakeLinearClient.project("p1", "Launch", T));

        assertThat(doc.externalId()).isEqualTo("project:p1");
        assertThat(doc.title()).isEqualTo("Linear project: Launch");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.linear.project");
        assertThat(doc.ownerName()).isEqualTo("Priya Nair");
        assertThat(doc.body()).contains("Status: started", "Lead: Priya Nair <priya@example.com>",
                "Target date: 2026-06-30", "Description:\nAbout Launch");
    }

    @Test
    void aDocumentKeepsItsContentAndNamesItsProject() {
        DocumentWrite doc = LinearRender.document(FakeLinearClient.doc("d1", "Launch plan", "p1", T));

        assertThat(doc.externalId()).isEqualTo("doc:d1");
        assertThat(doc.title()).isEqualTo("Linear doc: Launch plan");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.linear.document");
        assertThat(doc.ownerName()).isEqualTo("Asha Rao");
        assertThat(doc.body()).contains("Project: Launch", "Content of Launch plan");
    }

    @Test
    void aCommentWithNoAuthorOrDateStillRenders() {
        Issue base = FakeLinearClient.issue("i3", "ENG-14", "Odd", T);
        Issue issue = new Issue(base.id(), base.identifier(), base.title(), "d", "Todo", "Low", null, null, null,
                null, List.of(), null, null, null, null, T, base.url(), null,
                List.of(new Comment(null, "anonymous note", null)));

        assertThat(LinearRender.issue(issue).body()).contains("- Someone: anonymous note");
    }
}

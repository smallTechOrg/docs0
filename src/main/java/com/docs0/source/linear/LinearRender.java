package com.docs0.source.linear;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.linear.LinearClient.Comment;
import com.docs0.source.linear.LinearClient.Doc;
import com.docs0.source.linear.LinearClient.Issue;
import com.docs0.source.linear.LinearClient.Project;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Turns Linear items into plain text documents with labelled fields. Pure. */
final class LinearRender {

    static final String ISSUE_MIME = "application/vnd.linear.issue";
    static final String PROJECT_MIME = "application/vnd.linear.project";
    static final String DOCUMENT_MIME = "application/vnd.linear.document";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private LinearRender() {}

    static DocumentWrite issue(Issue issue) {
        StringBuilder body = new StringBuilder();
        field(body, "Status", orElse(issue.state(), "Unknown"));
        field(body, "Assignee", person(issue.assigneeName(), issue.assigneeEmail(), "Unassigned"));
        field(body, "Creator", person(issue.creatorName(), issue.creatorEmail(), null));
        field(body, "Project", issue.project());
        field(body, "Cycle", issue.cycle());
        field(body, "Labels", issue.labels() == null || issue.labels().isEmpty() ? null : String.join(", ", issue.labels()));
        field(body, "Priority", orElse(issue.priority(), "No priority"));
        field(body, "Due", issue.dueDate());
        field(body, "Parent", issue.parentIdentifier());
        field(body, "Created", day(issue.createdAt()));
        field(body, "Updated", day(issue.updatedAt()));
        field(body, "Link", issue.url());
        description(body, issue.description());
        if (issue.comments() != null && !issue.comments().isEmpty()) {
            body.append("\nComments (").append(issue.comments().size()).append("):\n");
            for (Comment c : issue.comments()) {
                body.append("- ").append(orElse(c.authorName(), "Someone"));
                if (c.createdAt() != null) {
                    body.append(" (").append(day(c.createdAt())).append(')');
                }
                body.append(": ").append(orElse(c.body(), "").strip()).append('\n');
            }
        }
        String assignee = issue.assigneeName() != null ? issue.assigneeName() : issue.creatorName();
        String email = issue.assigneeName() != null ? issue.assigneeEmail() : issue.creatorEmail();
        return new DocumentWrite("issue:" + issue.id(), issue.identifier() + ": " + issue.title(), ISSUE_MIME,
                issue.url(), assignee, email, issue.updatedAt(), body.toString());
    }

    static DocumentWrite project(Project project) {
        StringBuilder body = new StringBuilder();
        field(body, "Status", orElse(project.state(), "Unknown"));
        field(body, "Lead", person(project.leadName(), project.leadEmail(), "No lead"));
        field(body, "Target date", project.targetDate());
        field(body, "Updated", day(project.updatedAt()));
        field(body, "Link", project.url());
        description(body, project.description());
        return new DocumentWrite("project:" + project.id(), "Linear project: " + project.name(), PROJECT_MIME,
                project.url(), project.leadName(), project.leadEmail(), project.updatedAt(), body.toString());
    }

    static DocumentWrite document(Doc doc) {
        StringBuilder body = new StringBuilder();
        field(body, "Project", doc.projectName());
        field(body, "Author", doc.creatorName());
        field(body, "Updated", day(doc.updatedAt()));
        field(body, "Link", doc.url());
        body.append('\n').append(orElse(doc.content(), "").strip()).append('\n');
        return new DocumentWrite("doc:" + doc.id(), "Linear doc: " + orElse(doc.title(), "Untitled"), DOCUMENT_MIME,
                doc.url(), doc.creatorName(), null, doc.updatedAt(), body.toString());
    }

    private static void description(StringBuilder body, String description) {
        if (description != null && !description.isBlank()) {
            body.append("\nDescription:\n").append(description.strip()).append('\n');
        }
    }

    private static void field(StringBuilder body, String label, String value) {
        if (value != null && !value.isBlank()) {
            body.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String person(String name, String email, String none) {
        if (name == null || name.isBlank()) {
            return none;
        }
        return email == null || email.isBlank() ? name : name + " <" + email + ">";
    }

    private static String day(Instant instant) {
        return instant == null ? null : DAY.format(instant);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

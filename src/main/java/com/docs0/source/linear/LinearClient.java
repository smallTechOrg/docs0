package com.docs0.source.linear;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The network edge to Linear. Every call takes the API key explicitly, so a key can be tested
 * before it is stored. Throws {@link com.docs0.source.SourceAccessException} when Linear rejects
 * the key. Reads everything except {@link #createIssue}, the only write docs0 ever makes.
 */
public interface LinearClient {

    /** Who the key belongs to: the user and the workspace (organization) they are in. */
    record Viewer(String userName, String workspaceName) {}

    record Comment(String authorName, String body, Instant createdAt) {}

    record Issue(
            String id,
            String identifier,
            String title,
            String description,
            String state,
            String priority,
            String assigneeName,
            String assigneeEmail,
            String creatorName,
            String creatorEmail,
            List<String> labels,
            String project,
            String cycle,
            String dueDate,
            Instant createdAt,
            Instant updatedAt,
            String url,
            String parentIdentifier,
            List<Comment> comments) {}

    record Project(
            String id,
            String name,
            String description,
            String state,
            String leadName,
            String leadEmail,
            String targetDate,
            String url,
            Instant updatedAt) {}

    record Doc(
            String id,
            String title,
            String content,
            String projectId,
            String projectName,
            String creatorName,
            String url,
            Instant updatedAt) {}

    /** An item that was just created. */
    record Created(String identifier, String url) {}

    Viewer viewer(String apiKey) throws IOException;

    List<LinearTeam> teams(String apiKey) throws IOException;

    /** Every non-archived issue of the team, with its first comments. */
    List<Issue> issues(String apiKey, String teamId) throws IOException;

    /** Projects that belong to any of the teams. */
    List<Project> projects(String apiKey, List<String> teamIds) throws IOException;

    /** Every document in the workspace; the caller keeps those of projects it indexes. */
    List<Doc> documents(String apiKey) throws IOException;

    /** How many non-archived issues the team has; stops counting at {@code cap} and returns {@code cap}. */
    int countIssues(String apiKey, String teamId, int cap) throws IOException;

    /** Creates an issue. {@code priority} is Linear's number (1 urgent to 4 low, 0 none), or null to leave it unset. */
    Created createIssue(String apiKey, String teamId, String title, String description, Integer priority)
            throws IOException;
}

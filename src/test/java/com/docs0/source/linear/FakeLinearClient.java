package com.docs0.source.linear;

import com.docs0.source.SourceAccessException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory Linear. Records every key it is called with and every issue it is asked to create. */
public class FakeLinearClient implements LinearClient {

    public record CreateCall(String apiKey, String teamId, String title, String description, Integer priority) {}

    public static final String GOOD_KEY = "lin_api_good_key_123";

    public volatile Viewer viewer = new Viewer("Asha Rao", "Acme");
    public final List<LinearTeam> teams = new CopyOnWriteArrayList<>(List.of(
            new LinearTeam("team-eng", "ENG", "Engineering"), new LinearTeam("team-ops", "OPS", "Operations")));
    public final Map<String, List<Issue>> issuesByTeam = new LinkedHashMap<>();
    public final List<Project> projects = new CopyOnWriteArrayList<>();
    public final List<Doc> docs = new CopyOnWriteArrayList<>();
    public final List<String> keysSeen = new CopyOnWriteArrayList<>();
    public final List<CreateCall> creates = new CopyOnWriteArrayList<>();
    /** Thrown by every read when set. */
    public volatile IOException readFailure;
    public volatile IOException createFailure;
    public volatile Created createResult = new Created("ENG-99", "https://linear.app/acme/issue/ENG-99");
    /** When not null, any other key is rejected. */
    public volatile String acceptedKey;

    /** Back to the state of a new fake. */
    public void reset() {
        viewer = new Viewer("Asha Rao", "Acme");
        teams.clear();
        teams.addAll(List.of(new LinearTeam("team-eng", "ENG", "Engineering"),
                new LinearTeam("team-ops", "OPS", "Operations")));
        issuesByTeam.clear();
        projects.clear();
        docs.clear();
        keysSeen.clear();
        creates.clear();
        readFailure = null;
        createFailure = null;
        createResult = new Created("ENG-99", "https://linear.app/acme/issue/ENG-99");
        acceptedKey = null;
    }

    private void check(String apiKey) throws IOException {
        keysSeen.add(apiKey);
        if (acceptedKey != null && !acceptedKey.equals(apiKey)) {
            throw new SourceAccessException(HttpLinearClient.REJECTED);
        }
        if (readFailure != null) {
            throw readFailure;
        }
    }

    @Override
    public Viewer viewer(String apiKey) throws IOException {
        check(apiKey);
        return viewer;
    }

    @Override
    public List<LinearTeam> teams(String apiKey) throws IOException {
        check(apiKey);
        return List.copyOf(teams);
    }

    @Override
    public List<Issue> issues(String apiKey, String teamId) throws IOException {
        check(apiKey);
        return List.copyOf(issuesByTeam.getOrDefault(teamId, List.of()));
    }

    @Override
    public List<Project> projects(String apiKey, List<String> teamIds) throws IOException {
        check(apiKey);
        return List.copyOf(projects);
    }

    @Override
    public List<Doc> documents(String apiKey) throws IOException {
        check(apiKey);
        return List.copyOf(docs);
    }

    @Override
    public int countIssues(String apiKey, String teamId, int cap) throws IOException {
        check(apiKey);
        return Math.min(cap, issuesByTeam.getOrDefault(teamId, List.of()).size());
    }

    @Override
    public Created createIssue(String apiKey, String teamId, String title, String description, Integer priority)
            throws IOException {
        keysSeen.add(apiKey);
        creates.add(new CreateCall(apiKey, teamId, title, description, priority));
        if (createFailure != null) {
            throw createFailure;
        }
        return createResult;
    }

    /** An issue with plain defaults; use the record's wither-free constructor for anything special. */
    public static Issue issue(String id, String identifier, String title, Instant updatedAt) {
        return new Issue(id, identifier, title, "Details of " + title, "In Progress", "High", "Priya Nair",
                "priya@example.com", "Marco Diaz", "marco@example.com", List.of("backend"), "Launch", "Cycle 4",
                "2026-04-01", Instant.parse("2026-03-01T09:00:00Z"), updatedAt,
                "https://linear.app/acme/issue/" + identifier, null,
                new ArrayList<>(List.of(new Comment("Marco Diaz", "Looks good to me", Instant.parse("2026-03-02T10:00:00Z")))));
    }

    public static Project project(String id, String name, Instant updatedAt) {
        return new Project(id, name, "About " + name, "started", "Priya Nair", "priya@example.com", "2026-06-30",
                "https://linear.app/acme/project/" + id, updatedAt);
    }

    public static Doc doc(String id, String title, String projectId, Instant updatedAt) {
        return new Doc(id, title, "Content of " + title, projectId, "Launch", "Asha Rao",
                "https://linear.app/acme/document/" + id, updatedAt);
    }
}

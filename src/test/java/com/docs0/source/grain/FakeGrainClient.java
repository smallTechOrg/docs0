package com.docs0.source.grain;

import com.docs0.source.SourceAccessException;
import com.docs0.source.grain.GrainClient.Person;
import com.docs0.source.grain.GrainClient.Recording;
import com.docs0.source.grain.GrainClient.Segment;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory Grain. Records every token it is called with and every recording whose details were read. */
public class FakeGrainClient implements GrainClient {

    public static final String GOOD_TOKEN = "grain_pat_good_token_123";

    public volatile Account account = new Account("Acme Workspace", "workspace");
    /** What the list returns (without details), by id. */
    public final Map<String, Recording> recordings = new LinkedHashMap<>();
    public final List<String> tokensSeen = new CopyOnWriteArrayList<>();
    public final List<String> detailsRead = new CopyOnWriteArrayList<>();
    /** Recording ids whose details fail. */
    public final List<String> brokenDetails = new CopyOnWriteArrayList<>();
    public volatile IOException readFailure;
    /** When not null, any other token is rejected. */
    public volatile String acceptedToken;

    public void reset() {
        account = new Account("Acme Workspace", "workspace");
        recordings.clear();
        tokensSeen.clear();
        detailsRead.clear();
        brokenDetails.clear();
        readFailure = null;
        acceptedToken = null;
    }

    private void check(String token) throws IOException {
        tokensSeen.add(token);
        if (acceptedToken != null && !acceptedToken.equals(token)) {
            throw new SourceAccessException(HttpGrainClient.REJECTED);
        }
        if (readFailure != null) {
            throw readFailure;
        }
    }

    @Override
    public Account account(String token) throws IOException {
        check(token);
        return account;
    }

    @Override
    public List<Recording> recordings(String token, Instant since) throws IOException {
        check(token);
        return recordings.values().stream().filter(r -> !r.start().isBefore(since))
                .map(r -> new Recording(r.id(), r.title(), r.url(), r.start(), r.end(), r.durationMs(), r.recorders(),
                        r.participants(), null, List.of(), List.of(), List.of())).toList();
    }

    @Override
    public Recording details(String token, String recordingId) throws IOException {
        check(token);
        detailsRead.add(recordingId);
        if (brokenDetails.contains(recordingId)) {
            throw new IOException("Grain answered HTTP 500");
        }
        return recordings.get(recordingId);
    }

    @Override
    public int countRecordings(String token, Instant since, int cap) throws IOException {
        return Math.min(cap, recordings(token, since).size());
    }

    /** A recording with notes and a two-line transcript. */
    public static Recording recording(String id, String title, Instant start, Instant end) {
        return new Recording(id, title, "https://grain.com/share/" + id, start, end, end.toEpochMilli() - start.toEpochMilli(),
                List.of(new Person("Asha Rao", "asha@example.com")), List.of(new Person("Priya Nair", "priya@example.com")),
                "We agreed to ship on Friday.", List.of("[pending] Send the plan (Priya Nair)"), List.of("[00:01:05] Ship on Friday"),
                List.of(new Segment("Asha Rao", 1000, "Let us talk about the launch."),
                        new Segment("Priya Nair", 9000, "Friday works for me.")));
    }

    /** The same recording with no transcript yet. */
    public static Recording withoutTranscript(Recording r) {
        return new Recording(r.id(), r.title(), r.url(), r.start(), r.end(), r.durationMs(), r.recorders(),
                r.participants(), null, List.of(), List.of(), List.of());
    }
}

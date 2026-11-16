package com.docs0.source.grain;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The network edge to Grain. Every call takes the access token explicitly, so a token can be
 * tested before it is stored. Throws {@link com.docs0.source.SourceAccessException} when Grain
 * rejects the token. Read-only: docs0 never changes anything in Grain.
 */
public interface GrainClient {

    /** Whose token it is: the user (personal token) or the workspace (workspace token). */
    record Account(String name, String scope) {}

    record Person(String name, String email) {}

    /** One transcript line: who spoke, when (ms from the start) and what. */
    record Segment(String speaker, long startMs, String text) {}

    /**
     * A meeting recording. From the list only the first fields are filled; {@link #details} fills
     * the summary, action items, highlights and transcript too (the lists are then never null).
     */
    record Recording(
            String id,
            String title,
            String url,
            Instant start,
            Instant end,
            Long durationMs,
            List<Person> recorders,
            List<Person> participants,
            String summary,
            List<String> actionItems,
            List<String> highlights,
            List<Segment> transcript) {}

    Account account(String token) throws IOException;

    /** Every recording that started at or after {@code since}, without notes or transcript. */
    List<Recording> recordings(String token, Instant since) throws IOException;

    /** One recording with its AI summary, action items, highlights, participants and transcript. */
    Recording details(String token, String recordingId) throws IOException;

    /** How many recordings started at or after {@code since}; stops counting at {@code cap} and returns it. */
    int countRecordings(String token, Instant since, int cap) throws IOException;
}

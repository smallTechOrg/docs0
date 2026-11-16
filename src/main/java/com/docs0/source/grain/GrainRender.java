package com.docs0.source.grain;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.grain.GrainClient.Person;
import com.docs0.source.grain.GrainClient.Recording;
import com.docs0.source.grain.GrainClient.Segment;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Turns a Grain recording into one plain text document with labelled fields. Pure. */
final class GrainRender {

    static final String MIME = "application/vnd.grain.recording";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private GrainRender() {}

    /** True when the recording has any transcript text. */
    static boolean hasTranscript(Recording r) {
        return r.transcript() != null && r.transcript().stream().anyMatch(s -> s != null && !blank(s.text()));
    }

    /** The time docs0 compares to see whether a recording changed: when it ended, else when it started. */
    static Instant modified(Recording r) {
        return r.end() != null ? r.end() : r.start();
    }

    static DocumentWrite recording(Recording r) {
        StringBuilder body = new StringBuilder();
        field(body, "Date", r.start() == null ? null : DATE_TIME.format(r.start()));
        field(body, "Duration", duration(r));
        field(body, "Participants", people(r.participants()));
        field(body, "Recorded by", people(r.recorders()));
        field(body, "Link", r.url());

        if (!blank(r.summary())) {
            body.append("\nSummary:\n").append(r.summary().strip()).append('\n');
        }
        if (r.actionItems() != null && !r.actionItems().isEmpty()) {
            body.append("\nAction items:\n");
            r.actionItems().forEach(a -> body.append("- ").append(a).append('\n'));
        }
        if (r.highlights() != null && !r.highlights().isEmpty()) {
            body.append("\nHighlights:\n");
            r.highlights().forEach(h -> body.append("- ").append(h).append('\n'));
        }
        body.append("\nTranscript:\n");
        if (hasTranscript(r)) {
            transcript(body, r.transcript());
        } else {
            body.append("Not available yet.\n");
        }

        String title = blank(r.title()) ? "Meeting " + (r.start() == null ? r.id() : DAY.format(r.start())) : r.title().strip();
        Person owner = r.recorders() == null || r.recorders().isEmpty() ? null : r.recorders().get(0);
        return new DocumentWrite("recording:" + r.id(), title, MIME, r.url(),
                owner == null ? null : owner.name(), owner == null ? null : owner.email(), modified(r),
                body.toString());
    }

    /** One line per run of consecutive segments by the same speaker: {@code [hh:mm:ss] Speaker: text}. */
    private static void transcript(StringBuilder body, List<Segment> segments) {
        String speaker = null;
        long start = 0;
        StringBuilder text = null;
        for (Segment s : segments) {
            if (s == null || blank(s.text())) {
                continue;
            }
            String who = blank(s.speaker()) ? "Unknown" : s.speaker().strip();
            if (text != null && who.equals(speaker)) {
                text.append(' ').append(s.text().strip());
                continue;
            }
            line(body, start, speaker, text);
            speaker = who;
            start = s.startMs();
            text = new StringBuilder(s.text().strip());
        }
        line(body, start, speaker, text);
    }

    private static void line(StringBuilder body, long startMs, String speaker, StringBuilder text) {
        if (text != null) {
            body.append(clock(startMs)).append(' ').append(speaker).append(": ").append(text).append('\n');
        }
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("[%02d:%02d:%02d]", s / 3600, s / 60 % 60, s % 60);
    }

    private static String duration(Recording r) {
        Long ms = r.durationMs();
        if (ms == null && r.start() != null && r.end() != null) {
            ms = r.end().toEpochMilli() - r.start().toEpochMilli();
        }
        if (ms == null || ms <= 0) {
            return null;
        }
        long minutes = Math.max(1, Math.round(ms / 60000.0));
        return minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min";
    }

    private static String people(List<Person> people) {
        if (people == null || people.isEmpty()) {
            return null;
        }
        List<String> parts = people.stream().filter(p -> p != null && (!blank(p.name()) || !blank(p.email())))
                .map(p -> blank(p.name()) ? p.email() : blank(p.email()) ? p.name() : p.name() + " <" + p.email() + ">")
                .toList();
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static void field(StringBuilder body, String label, String value) {
        if (!blank(value)) {
            body.append(label).append(": ").append(value).append('\n');
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

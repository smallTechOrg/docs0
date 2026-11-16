package com.docs0.source.grain;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.grain.GrainClient.Recording;
import com.docs0.source.grain.GrainClient.Segment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrainRenderTest {

    private static final Instant START = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant END = Instant.parse("2026-03-01T09:45:00Z");

    @Test
    void aRecordingBecomesOneDocumentWithLabelledFields() {
        DocumentWrite doc = GrainRender.recording(FakeGrainClient.recording("r1", "Launch sync", START, END));

        assertThat(doc.externalId()).isEqualTo("recording:r1");
        assertThat(doc.title()).isEqualTo("Launch sync");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.grain.recording");
        assertThat(doc.url()).isEqualTo("https://grain.com/share/r1");
        assertThat(doc.ownerName()).isEqualTo("Asha Rao");
        assertThat(doc.ownerEmail()).isEqualTo("asha@example.com");
        assertThat(doc.modifiedAt()).isEqualTo(END);
        assertThat(doc.body()).contains("Date: 2026-03-01 09:00 UTC", "Duration: 45 min",
                "Participants: Priya Nair <priya@example.com>", "Recorded by: Asha Rao <asha@example.com>",
                "Link: https://grain.com/share/r1", "Summary:\nWe agreed to ship on Friday.",
                "Action items:\n- [pending] Send the plan (Priya Nair)", "Highlights:\n- [00:01:05] Ship on Friday",
                "Transcript:\n[00:00:01] Asha Rao: Let us talk about the launch.\n[00:00:09] Priya Nair: Friday works for me.");
    }

    @Test
    void consecutiveLinesBySameSpeakerAreMerged() {
        Recording r = new Recording("r2", "Standup", null, START, END, null, List.of(), List.of(), null, List.of(),
                List.of(), List.of(new Segment("Asha", 61_000, "First."), new Segment("Asha", 63_000, "Second."),
                        new Segment("Priya", 70_000, "Reply."), new Segment("Asha", 3_700_000, "Later."),
                        new Segment(null, 3_800_000, "  "), new Segment(null, 3_900_000, "Who?")));

        String body = GrainRender.recording(r).body();

        assertThat(body).contains("[00:01:01] Asha: First. Second.\n[00:01:10] Priya: Reply.\n"
                + "[01:01:40] Asha: Later.\n[01:05:00] Unknown: Who?\n");
    }

    @Test
    void aMissingTranscriptSaysSoAndStillIndexesWhatExists() {
        Recording r = FakeGrainClient.withoutTranscript(FakeGrainClient.recording("r3", " ", START, END));

        DocumentWrite doc = GrainRender.recording(r);

        assertThat(GrainRender.hasTranscript(r)).isFalse();
        assertThat(doc.title()).isEqualTo("Meeting 2026-03-01");
        assertThat(doc.body()).contains("Transcript:\nNot available yet.").doesNotContain("Summary:");
    }

    @Test
    void missingOptionalFieldsAreLeftOutAndTheStartStandsInForTheEndTime() {
        Recording r = new Recording("r4", "Quick call", null, START, null, null, null, null, null, null, null, null);

        DocumentWrite doc = GrainRender.recording(r);

        assertThat(doc.modifiedAt()).isEqualTo(START);
        assertThat(doc.ownerName()).isNull();
        assertThat(doc.body()).doesNotContain("Participants:", "Duration:", "Link:", "Highlights:", "Action items:");
    }
}

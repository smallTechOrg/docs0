package com.docs0.source.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.Message;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Text handling and the thread-to-document rendering, without a network or a database. */
class GmailTextTest {

    private static Message message(String id, String text, Instant at, String... attachments) {
        return new Message(id, at, "Ana <ana@example.com>", "me@example.com", "boss@example.com",
                "Mon, 3 Nov 2026 09:00:00 +0000", text, List.of(attachments));
    }

    @Test
    void entitiesAreDecodedAndUnknownOnesLeftAlone() {
        assertThat(GmailText.decodeEntities("a &amp; b &lt;c&gt; &quot;d&quot; &#65;&#x42; &bogus; &#99999999;"))
                .isEqualTo("a & b <c> \"d\" AB &bogus; &#99999999;");
    }

    @Test
    void aThreadBecomesOneDocumentWithHeadersAndAttachmentNames() {
        MailThread thread = new MailThread("t9", "55", "Quarterly plan", List.of(
                message("m1", "First message.", Instant.parse("2026-11-03T09:00:00Z"), "plan.pdf"),
                message("m2", "Second message.", Instant.parse("2026-11-04T10:30:00Z"))));

        DocumentWrite doc = GmailRender.thread("sam@example.com", thread);

        assertThat(doc.externalId()).isEqualTo("gmail:sam@example.com:t9");
        assertThat(doc.title()).isEqualTo("Quarterly plan");
        assertThat(doc.url()).isEqualTo("https://mail.google.com/mail/u/sam@example.com/#all/t9");
        assertThat(doc.mimeType()).isEqualTo("application/vnd.google.gmail.thread");
        assertThat(doc.ownerEmail()).isEqualTo("sam@example.com");
        assertThat(doc.ownerName()).isEqualTo("sam");
        assertThat(doc.modifiedAt()).isEqualTo(Instant.parse("2026-11-04T10:30:00Z"));
        assertThat(doc.body()).startsWith("From: Ana <ana@example.com>\nTo: me@example.com\nCc: boss@example.com\n"
                + "Date: Mon, 3 Nov 2026 09:00:00 +0000\n\nFirst message.\n\nAttachments: plan.pdf\n\n---\n\nFrom:")
                .contains("Second message.");
        assertThat(GmailRender.mailboxOf(doc.externalId())).isEqualTo("sam@example.com");
        assertThat(GmailRender.mailboxOf("thread:C1:1.2")).isEmpty();
    }

    @Test
    void aMissingSubjectFallsBackAndAnEmptyThreadIsNothing() {
        MailThread thread = new MailThread("t1", "1", null, List.of(message("m1", "hi", null)));

        assertThat(GmailRender.thread("sam@example.com", thread).title()).isEqualTo("(no subject)");
        assertThat(GmailRender.thread("sam@example.com", new MailThread("t2", "1", "x", List.of()))).isNull();
    }

    @Test
    void aHugeThreadIsCutAtAboutOneHundredThousandCharacters() {
        String big = "word ".repeat(30_000);
        MailThread thread = new MailThread("t1", "1", "Big", List.of(
                message("m1", big, Instant.parse("2026-11-03T09:00:00Z")),
                message("m2", big, Instant.parse("2026-11-04T09:00:00Z"))));

        String body = GmailRender.thread("sam@example.com", thread).body();

        assertThat(body.length()).isLessThanOrEqualTo(GmailRender.MAX_CHARS);
        assertThat(body).endsWith(GmailRender.CUT);
    }
}

package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.source.gmail.GmailClient;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.ThreadPage;
import com.docs0.source.gmail.GoogleTokenSource;
import com.docs0.source.gmail.HttpGmailClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-credential, read-only check of the Gmail client with a service account that has domain-wide
 * delegation: reads one mailbox's profile, lists a page of threads and fetches a few in full. Prints counts
 * only: never an address, a subject, a message or the key.
 *
 * <p>Skipped unless DOCS0_LIVE=1, DOCS0_GMAIL_KEY_FILE (path to the JSON key) and DOCS0_GMAIL_MAILBOX are set.
 * Run by hand, never as part of the gate.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
@EnabledIfEnvironmentVariable(named = "DOCS0_GMAIL_KEY_FILE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DOCS0_GMAIL_MAILBOX", matches = ".+")
class LiveGmailCheck {

    @Test
    void readsTheProfileAndAFewThreads() throws Exception {
        String key = Files.readString(Path.of(System.getenv("DOCS0_GMAIL_KEY_FILE")));
        String mailbox = System.getenv("DOCS0_GMAIL_MAILBOX");
        GmailClient client = new HttpGmailClient(new GoogleTokenSource());

        GmailClient.Profile profile = client.profile(key, mailbox);
        System.out.println("GMAIL messages=" + profile.messagesTotal() + " threads=" + profile.threadsTotal());

        ThreadPage page = client.threads(key, mailbox,
                "newer_than:14d -in:spam -in:trash -in:draft -category:promotions -category:social -category:forums", null);
        System.out.println("GMAIL threads in the last 14 days (first page)=" + page.threads().size()
                + " more=" + (page.nextPageToken() != null));

        int read = 0;
        int messages = 0;
        int withText = 0;
        int attachments = 0;
        for (GmailClient.ThreadRef ref : page.threads().stream().limit(3).toList()) {
            MailThread thread = client.thread(key, mailbox, ref.id());
            if (thread == null) {
                continue;
            }
            read++;
            messages += thread.messages().size();
            for (GmailClient.Message m : thread.messages()) {
                withText += m.text() != null && !m.text().isBlank() ? 1 : 0;
                attachments += m.attachments().size();
            }
        }
        System.out.println("GMAIL read " + read + " threads, " + messages + " messages, " + withText
                + " with text, " + attachments + " attachments named");
        assertThat(profile.messagesTotal()).isGreaterThanOrEqualTo(0);
    }
}

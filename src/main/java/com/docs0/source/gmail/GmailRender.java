package com.docs0.source.gmail;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.Message;
import java.time.Instant;
import java.util.List;

/** How a mail thread becomes a document. No network, no database. */
final class GmailRender {

    private GmailRender() {}

    static final String MIME = "application/vnd.google.gmail.thread";
    static final int MAX_CHARS = 100_000;
    static final String CUT = "\n\n[The rest of this thread is not shown.]";

    static String externalId(String mailbox, String threadId) {
        return "gmail:" + mailbox + ":" + threadId;
    }

    static String prefix(String mailbox) {
        return "gmail:" + mailbox + ":";
    }

    /** The mailbox an external id belongs to, or "" when it is not one of ours. */
    static String mailboxOf(String externalId) {
        String[] parts = externalId.split(":", 3);
        return parts.length == 3 && parts[0].equals("gmail") ? parts[1] : "";
    }

    /** Null when the thread has no messages left to show. */
    static DocumentWrite thread(String mailbox, MailThread thread) {
        List<Message> messages = thread.messages();
        if (messages.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        Instant last = null;
        for (Message m : messages) {
            if (body.length() > 0) {
                body.append("\n\n---\n\n");
            }
            line(body, "From", m.from());
            line(body, "To", m.to());
            line(body, "Cc", m.cc());
            line(body, "Date", m.dateHeader());
            body.append('\n').append(m.text() == null ? "" : m.text().strip());
            if (!m.attachments().isEmpty()) {
                body.append("\n\nAttachments: ").append(String.join(", ", m.attachments()));
            }
            if (m.date() != null && (last == null || m.date().isAfter(last))) {
                last = m.date();
            }
            if (body.length() > MAX_CHARS) {
                break;
            }
        }
        String text = body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS - CUT.length()) + CUT : body.toString();
        String subject = thread.subject() == null || thread.subject().isBlank() ? "(no subject)" : thread.subject().strip();
        return new DocumentWrite(externalId(mailbox, thread.id()), subject, MIME,
                "https://mail.google.com/mail/u/" + mailbox + "/#all/" + thread.id(), owner(mailbox), mailbox, last,
                text);
    }

    private static String owner(String mailbox) {
        return mailbox.substring(0, mailbox.indexOf('@'));
    }

    private static void line(StringBuilder body, String label, String value) {
        if (value != null && !value.isBlank()) {
            body.append(label).append(": ").append(value.strip()).append('\n');
        }
    }
}

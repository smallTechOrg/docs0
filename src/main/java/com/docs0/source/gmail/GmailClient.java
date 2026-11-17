package com.docs0.source.gmail;

import com.docs0.source.SourceAccessException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The network edge to Gmail and the Workspace directory. Every call takes the service account key
 * (the JSON the admin pasted) and acts as the mailbox it names, through domain-wide delegation.
 * Strictly read-only. Throws {@link SourceAccessException} with a plain-language message when
 * Google refuses; {@link KeyRejectedException} when the key itself is refused, which concerns
 * every mailbox.
 */
public interface GmailClient {

    /** The service account key is not accepted at all: the whole run has to stop. */
    class KeyRejectedException extends SourceAccessException {
        public KeyRejectedException(String message) {
            super(message);
        }
    }

    record Profile(String email, long messagesTotal, long threadsTotal) {}

    /** One thread of a listing. {@code historyId} changes whenever the thread does. */
    record ThreadRef(String id, String historyId) {}

    record ThreadPage(List<ThreadRef> threads, String nextPageToken) {}

    /** One message with its text already extracted. Attachments are only named, never read. */
    record Message(String id, Instant date, String from, String to, String cc, String dateHeader, String text,
                   List<String> attachments) {}

    /** A thread with its messages, oldest first. Spam, trash and drafts are left out of {@code messages}. */
    record MailThread(String id, String historyId, String subject, List<Message> messages) {}

    /** A person of the Workspace directory. */
    record DirectoryUser(String email, String name) {}

    /** What the mailbox holds (the lightest call there is; also proves the delegation works). */
    Profile profile(String keyJson, String mailbox) throws IOException;

    /** One page of thread ids matching the Gmail search {@code query}; {@code pageToken} is null for the first. */
    ThreadPage threads(String keyJson, String mailbox, String query, String pageToken) throws IOException;

    /** One thread in full, or null when it no longer exists. */
    MailThread thread(String keyJson, String mailbox, String threadId) throws IOException;

    /** The non-suspended people of the Workspace, read as {@code adminEmail}. */
    List<DirectoryUser> directory(String keyJson, String adminEmail) throws IOException;
}

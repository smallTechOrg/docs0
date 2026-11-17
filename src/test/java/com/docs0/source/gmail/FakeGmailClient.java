package com.docs0.source.gmail;

import com.docs0.source.SourceAccessException;
import com.docs0.source.gmail.GmailClient.DirectoryUser;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.Message;
import com.docs0.source.gmail.GmailClient.Profile;
import com.docs0.source.gmail.GmailClient.ThreadPage;
import com.docs0.source.gmail.GmailClient.ThreadRef;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory Gmail. Records which mailbox acted and which threads were read in full. */
public class FakeGmailClient implements GmailClient {

    /** A real-looking service account key is made by {@link TestKeys}; the fake only needs some text. */
    public static final String KEY = "{\"fake\":\"key\"}";

    /** Mailbox -> thread id -> thread. Listing order is insertion order. */
    public final Map<String, Map<String, MailThread>> mailboxes = new LinkedHashMap<>();
    /** Mailbox -> why every call for it fails with a plain refusal. */
    public final Map<String, String> refusals = new LinkedHashMap<>();
    public final List<DirectoryUser> directory = new CopyOnWriteArrayList<>();
    public final List<String> threadsRead = new CopyOnWriteArrayList<>();
    public final List<String> queries = new CopyOnWriteArrayList<>();
    public final List<String> keysSeen = new CopyOnWriteArrayList<>();
    /** Thread ids whose fetch fails with an IOException. */
    public final List<String> brokenThreads = new CopyOnWriteArrayList<>();
    public volatile IOException listFailure;
    public volatile boolean keyRejected;
    public volatile String directoryAdminSeen;
    public volatile SourceAccessException directoryFailure;
    public int pageSize = 100;

    public void reset() {
        mailboxes.clear();
        refusals.clear();
        directory.clear();
        threadsRead.clear();
        queries.clear();
        keysSeen.clear();
        brokenThreads.clear();
        listFailure = null;
        keyRejected = false;
        directoryAdminSeen = null;
        directoryFailure = null;
    }

    /** Adds (or replaces) a one-message thread; the history id is the version. */
    public MailThread put(String mailbox, String id, String historyId, String subject, String text, Instant at) {
        Message m = new Message("m-" + id, at, "Ana <ana@example.com>", mailbox, null, "Mon, 3 Nov 2026 09:00:00 +0000",
                text, List.of());
        MailThread thread = new MailThread(id, historyId, subject, List.of(m));
        mailboxes.computeIfAbsent(mailbox, k -> new LinkedHashMap<>()).put(id, thread);
        return thread;
    }

    public void remove(String mailbox, String id) {
        mailboxes.getOrDefault(mailbox, Map.of()).remove(id);
    }

    private void check(String keyJson, String mailbox) throws IOException {
        keysSeen.add(keyJson);
        if (keyRejected) {
            throw new KeyRejectedException(HttpGmailClient.KEY_REJECTED);
        }
        String refusal = refusals.get(mailbox);
        if (refusal != null) {
            throw new SourceAccessException(refusal);
        }
    }

    @Override
    public Profile profile(String keyJson, String mailbox) throws IOException {
        check(keyJson, mailbox);
        int n = mailboxes.getOrDefault(mailbox, Map.of()).size();
        return new Profile(mailbox, n * 10L, n);
    }

    @Override
    public ThreadPage threads(String keyJson, String mailbox, String query, String pageToken) throws IOException {
        check(keyJson, mailbox);
        queries.add(query);
        if (listFailure != null) {
            throw listFailure;
        }
        List<ThreadRef> all = new ArrayList<>();
        mailboxes.getOrDefault(mailbox, Map.of()).values().forEach(t -> all.add(new ThreadRef(t.id(), t.historyId())));
        int from = pageToken == null ? 0 : Integer.parseInt(pageToken);
        int to = Math.min(all.size(), from + pageSize);
        return new ThreadPage(all.subList(from, to), to < all.size() ? String.valueOf(to) : null);
    }

    @Override
    public MailThread thread(String keyJson, String mailbox, String threadId) throws IOException {
        check(keyJson, mailbox);
        if (brokenThreads.contains(threadId)) {
            throw new IOException("Google answered HTTP 500");
        }
        threadsRead.add(mailbox + ":" + threadId);
        return mailboxes.getOrDefault(mailbox, Map.of()).get(threadId);
    }

    @Override
    public List<DirectoryUser> directory(String keyJson, String adminEmail) throws IOException {
        keysSeen.add(keyJson);
        directoryAdminSeen = adminEmail;
        if (directoryFailure != null) {
            throw directoryFailure;
        }
        return List.copyOf(directory);
    }
}

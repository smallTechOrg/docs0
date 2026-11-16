package com.docs0.source.slack;

import com.docs0.slack.SlackMessenger;
import com.slack.api.model.block.LayoutBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** An in-memory Slack workspace for the history tests. Records what was asked. */
public final class FakeSlack implements SlackMessenger, com.docs0.slack.SlackMessengers {

    /** A single-workspace fake: every tenant talks to this one workspace. */
    @Override
    public SlackMessenger forTenant(long tenantId) {
        return workspaces.getOrDefault(tenantId, this);
    }


    public static final String BOT = "UBOT";

    public final List<ChannelInfo> channels = new ArrayList<>();
    public final Map<String, List<SlackMessage>> history = new HashMap<>();
    public final Map<String, List<SlackMessage>> threads = new HashMap<>();
    public final Map<String, Set<String>> members = new HashMap<>();
    public final Map<String, SlackUser> users = new LinkedHashMap<>();

    public final List<String> historyCalls = new CopyOnWriteArrayList<>();
    public final List<Instant> historyFrom = new CopyOnWriteArrayList<>();
    public final List<String> replyCalls = new CopyOnWriteArrayList<>();
    public final List<String> joins = new CopyOnWriteArrayList<>();
    public final List<String> permalinkCalls = new CopyOnWriteArrayList<>();
    public final Set<String> failingHistory = ConcurrentHashMap.newKeySet();
    public final Set<String> failingReplies = ConcurrentHashMap.newKeySet();
    public final Set<String> failingJoins = ConcurrentHashMap.newKeySet();
    public final Set<String> failingMembers = ConcurrentHashMap.newKeySet();
    public volatile boolean permalinksFail;
    public volatile boolean listFails;

    public volatile java.util.Set<String> scopes = Set.of();

    /** Tenants that have a workspace of their own; every other tenant talks to this one. */
    private final Map<Long, SlackMessenger> workspaces = new ConcurrentHashMap<>();

    /** Gives a tenant its own workspace, for tests with two companies. */
    public FakeSlack workspaceFor(long tenantId, SlackMessenger workspace) {
        workspaces.put(tenantId, workspace);
        return this;
    }

    /** Back to an empty workspace, for tests that share one instance. */
    public void reset() {
        channels.clear();
        history.clear();
        threads.clear();
        members.clear();
        users.clear();
        historyCalls.clear();
        historyFrom.clear();
        replyCalls.clear();
        joins.clear();
        permalinkCalls.clear();
        failingHistory.clear();
        failingReplies.clear();
        failingJoins.clear();
        failingMembers.clear();
        permalinksFail = false;
        listFails = false;
        lookupFails = false;
        postsFail = false;
        dmsOpened.clear();
        sent.clear();
        scopes = Set.of();
        workspaces.clear();
    }

    // ---- building the workspace ----

    public FakeSlack channel(String id, String name, boolean isPrivate, boolean member) {
        channels.add(new ChannelInfo(id, name, isPrivate, member, 3));
        return this;
    }

    public FakeSlack user(String id, String name, String email) {
        users.put(id, new SlackUser(id, email, name, false, false));
        return this;
    }

    public FakeSlack members(String channel, String... ids) {
        members.put(channel, Set.of(ids));
        return this;
    }

    /** A plain message. {@code ts} is epoch seconds plus a counter, like Slack's. */
    public static SlackMessage msg(String ts, String user, String text) {
        return new SlackMessage(ts, null, user, null, null, null, text, 0, null, null, List.of());
    }

    public static SlackMessage withSubtype(String ts, String user, String subtype, String text) {
        return new SlackMessage(ts, null, user, null, null, subtype, text, 0, null, null, List.of());
    }

    public static SlackMessage root(String ts, String user, String text, int replies, String latestReply) {
        return new SlackMessage(ts, ts, user, null, null, null, text, replies, latestReply, null, List.of());
    }

    public static SlackMessage reply(String ts, String threadTs, String user, String text) {
        return new SlackMessage(ts, threadTs, user, null, null, null, text, 0, null, null, List.of());
    }

    public static String ts(Instant at) {
        return at.getEpochSecond() + ".000100";
    }

    public FakeSlack posts(String channel, SlackMessage... messages) {
        history.computeIfAbsent(channel, c -> new ArrayList<>()).addAll(List.of(messages));
        return this;
    }

    public FakeSlack thread(String channel, SlackMessage... messages) {
        threads.put(channel + ":" + messages[0].ts(), List.of(messages));
        return this;
    }

    // ---- SlackMessenger ----

    @Override
    public List<ChannelInfo> listChannels() {
        if (listFails) {
            throw new SlackCallException("conversations.list", "network_error", null);
        }
        return List.copyOf(channels);
    }

    @Override
    public List<SlackMessage> history(String channel, Instant oldest) {
        historyCalls.add(channel);
        historyFrom.add(oldest);
        if (failingHistory.contains(channel)) {
            throw new SlackCallException("conversations.history", "internal_error", null);
        }
        return history.getOrDefault(channel, List.of()).stream()
                .filter(m -> !SlackHistoryRender.instant(m.ts()).isBefore(oldest)).toList();
    }

    @Override
    public List<SlackMessage> replies(String channel, String threadTs) {
        replyCalls.add(channel + ":" + threadTs);
        if (failingReplies.contains(channel + ":" + threadTs)) {
            throw new SlackCallException("conversations.replies", "internal_error", null);
        }
        List<SlackMessage> thread = threads.get(channel + ":" + threadTs);
        if (thread == null) {
            throw new SlackCallException("conversations.replies", "thread_not_found", null);
        }
        return thread;
    }

    @Override
    public String permalink(String channel, String ts) {
        permalinkCalls.add(channel + ":" + ts);
        if (permalinksFail) {
            throw new SlackCallException("chat.getPermalink", "message_not_found", null);
        }
        return "https://example.slack.com/archives/" + channel + "/p" + ts.replace(".", "");
    }

    @Override
    public void joinChannel(String channel) {
        if (failingJoins.contains(channel)) {
            throw new SlackCallException("conversations.join", "missing_scope", null);
        }
        joins.add(channel);
        channels.replaceAll(c -> c.id().equals(channel)
                ? new ChannelInfo(c.id(), c.name(), c.isPrivate(), true, c.memberCount()) : c);
    }

    @Override
    public SlackUser userInfo(String userId) {
        SlackUser user = users.get(userId);
        if (user == null) {
            throw new SlackCallException("users.info", "user_not_found", null);
        }
        return user;
    }

    @Override
    public Set<String> botScopes() {
        return scopes;
    }

    /** Set to make the next email lookup or DM fail, like a Slack outage. */
    public volatile boolean lookupFails;
    public final List<String> dmsOpened = new CopyOnWriteArrayList<>();
    /** Messages posted with {@link #postMessage}: channel, thread, text. */
    public record Sent(String channel, String threadTs, String text, List<LayoutBlock> blocks) {}
    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public volatile boolean postsFail;

    @Override
    public java.util.Optional<String> lookupByEmail(String email) {
        if (lookupFails) {
            throw new SlackCallException("users.lookupByEmail", "internal_error", null);
        }
        return users.values().stream().filter(u -> u.email() != null && u.email().equalsIgnoreCase(email))
                .map(SlackUser::id).findFirst();
    }

    @Override
    public String openDm(String userId) {
        dmsOpened.add(userId);
        return "D" + userId;
    }

    @Override
    public Set<String> channelMembers(String channel) {
        if (failingMembers.contains(channel)) {
            throw new SlackCallException("conversations.members", "internal_error", null);
        }
        return members.getOrDefault(channel, Set.of());
    }

    @Override
    public String botUserId() {
        return BOT;
    }

    @Override
    public String postMessage(String channel, String threadTs, String text, List<LayoutBlock> blocks) {
        if (postsFail) {
            throw new SlackCallException("chat.postMessage", "not_in_channel", null);
        }
        sent.add(new Sent(channel, threadTs, text, blocks));
        return "7000.%04d".formatted(sent.size());
    }

    @Override
    public void postEphemeral(String channel, String threadTs, String user, String text) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateMessage(String channel, String ts, String text, List<LayoutBlock> blocks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String userDisplayName(String userId) {
        throw new UnsupportedOperationException();
    }
}

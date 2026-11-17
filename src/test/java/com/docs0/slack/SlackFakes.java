package com.docs0.slack;

import com.docs0.agent.Agent;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.slack.api.model.block.LayoutBlock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** In-memory stand-ins for the network edges and the other slices' beans. */
final class SlackFakes {

    private SlackFakes() {}

    static Agent.AgentResult result(Agent.Kind kind, String answer) {
        return new Agent.AgentResult(kind, answer, Agent.Confidence.HIGH, List.of(), List.of(), List.of(), 1, 0, 0,
                BigDecimal.ZERO, 0, kind == Agent.Kind.ERROR ? "model exploded: secret-internal-detail" : null);
    }

    /** Records every Slack call instead of making it. */
    static final class FakeMessenger implements SlackMessenger, com.docs0.slack.SlackMessengers {

        /** A single-workspace fake: every tenant talks to this one workspace. */
        @Override
        public SlackMessenger forTenant(long tenantId) {
            return this;
        }

        record Posted(String channel, String threadTs, String text, List<LayoutBlock> blocks, String ts) {}

        record Updated(String channel, String ts, String text, List<LayoutBlock> blocks) {}

        final List<Posted> posts = new CopyOnWriteArrayList<>();
        final List<Updated> updates = new CopyOnWriteArrayList<>();
        final AtomicInteger memberLookups = new AtomicInteger();
        private final AtomicInteger nextTs = new AtomicInteger();
        volatile Set<String> members = Set.of("U1", "U2", "UBOT");
        volatile boolean memberLookupFails;
        volatile boolean rejectBlocks;
        volatile boolean rejectAllUpdates;
        volatile boolean postsFail;
        /** When set, a post whose text matches is rejected by Slack; other posts go through. */
        volatile java.util.function.Predicate<String> failPostWhen;
        final java.util.Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean nameLookupFails;

        @Override
        public String postMessage(String channel, String threadTs, String text, List<LayoutBlock> blocks) {
            if (postsFail || (failPostWhen != null && failPostWhen.test(text))) {
                throw new SlackCallException("chat.postMessage", "channel_not_found", null);
            }
            String ts = "9000.%04d".formatted(nextTs.incrementAndGet());
            posts.add(new Posted(channel, threadTs, text, blocks, ts));
            return ts;
        }

        record Ephemeral(String channel, String threadTs, String user, String text) {}

        final List<Ephemeral> ephemerals = new CopyOnWriteArrayList<>();

        @Override
        public void postEphemeral(String channel, String threadTs, String user, String text) {
            ephemerals.add(new Ephemeral(channel, threadTs, user, text));
        }

        @Override
        public void updateMessage(String channel, String ts, String text, List<LayoutBlock> blocks) {
            if (rejectAllUpdates || (rejectBlocks && blocks != null && !blocks.isEmpty())) {
                throw new SlackCallException("chat.update", "invalid_blocks", null);
            }
            updates.add(new Updated(channel, ts, text, blocks));
        }

        @Override
        public Set<String> channelMembers(String channel) {
            memberLookups.incrementAndGet();
            if (memberLookupFails || failingMembers.contains(channel)) {
                throw new SlackCallException("conversations.members", "missing_scope", null);
            }
            return channelMembers.getOrDefault(channel, members);
        }

        /** Channels Slack says are public. Every other channel is private, as before the public-channel rule. */
        final Set<String> publicChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicInteger privacyLookups = new AtomicInteger();
        volatile boolean privacyLookupFails;

        @Override
        public ChannelPrivacy channelPrivacy(String channel) {
            privacyLookups.incrementAndGet();
            if (privacyLookupFails) {
                throw new SlackCallException("conversations.info", "missing_scope", null);
            }
            return publicChannels.contains(channel) ? ChannelPrivacy.PUBLIC : ChannelPrivacy.PRIVATE;
        }

        @Override
        public String userDisplayName(String userId) {
            if (nameLookupFails) {
                throw new SlackCallException("users.info", "missing_scope", null);
            }
            return names.get(userId);
        }

        @Override
        public String botUserId() {
            return "UBOT";
        }

        // ---- reading workspace content ----

        final java.util.Map<String, SlackUser> users = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger userLookups = new AtomicInteger();
        volatile boolean userInfoFails;
        volatile String userInfoError = "ratelimited";
        volatile Set<String> scopes = Set.of();
        volatile List<ChannelInfo> channels = List.of();
        final java.util.Map<String, Set<String>> channelMembers = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, List<SlackMessage>> historyByChannel = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, List<SlackMessage>> threads = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> historyCalls = new CopyOnWriteArrayList<>();
        final List<String> replyCalls = new CopyOnWriteArrayList<>();
        final List<String> joins = new CopyOnWriteArrayList<>();
        final List<java.time.Instant> historyFrom = new CopyOnWriteArrayList<>();
        final Set<String> failingHistory = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<String> failingJoins = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<String> failingMembers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean permalinksFail;
        volatile boolean listFails;

        @Override
        public List<ChannelInfo> listChannels() {
            if (listFails) {
                throw new SlackCallException("conversations.list", "network_error", null);
            }
            return channels;
        }

        @Override
        public List<SlackMessage> history(String channel, java.time.Instant oldest) {
            historyCalls.add(channel);
            historyFrom.add(oldest);
            if (failingHistory.contains(channel)) {
                throw new SlackCallException("conversations.history", "internal_error", null);
            }
            return historyByChannel.getOrDefault(channel, List.of());
        }

        @Override
        public List<SlackMessage> replies(String channel, String threadTs) {
            replyCalls.add(channel + ":" + threadTs);
            List<SlackMessage> thread = threads.get(channel + ":" + threadTs);
            if (thread == null) {
                throw new SlackCallException("conversations.replies", "thread_not_found", null);
            }
            return thread;
        }

        @Override
        public String permalink(String channel, String ts) {
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
        }

        @Override
        public SlackUser userInfo(String userId) {
            userLookups.incrementAndGet();
            if (userInfoFails) {
                throw new SlackCallException("users.info", userInfoError, null);
            }
            SlackUser user = users.get(userId);
            return user != null ? user : new SlackUser(userId, null, names.getOrDefault(userId, userId), false, false);
        }

        @Override
        public Set<String> botScopes() {
            return scopes;
        }

        @Override
        public java.util.Optional<String> lookupByEmail(String email) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String openDm(String userId) {
            throw new UnsupportedOperationException();
        }

        Posted onlyPost() {
            if (posts.size() != 1) {
                throw new AssertionError("expected exactly one posted message but got " + posts);
            }
            return posts.get(0);
        }

        Updated lastUpdate() {
            return updates.get(updates.size() - 1);
        }
    }

    /** Answers with whatever {@link #behaviour} returns and records each request. */
    static final class FakeAgent implements Agent {

        final List<AgentRequest> requests = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile BiFunction<AgentRequest, ProgressListener, AgentResult> behaviour =
                (request, progress) -> result(Kind.ANSWER, "Answer to: " + request.question());

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public AgentResult answer(AgentRequest request, ProgressListener progress) {
            requests.add(request);
            return behaviour.apply(request, progress);
        }
    }

    /** A fake model for the one-shot classification: answers with {@link #verdict} and records what it was asked. */
    static final class FakeLlm implements com.docs0.agent.LlmClient {

        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile String kind = "correction";
        volatile String fact = "";
        volatile boolean fails;

        @Override
        public LlmTurn oneShot(String systemPrompt, String userText, ToolSpec tool) {
            asked.add(userText);
            if (fails) {
                throw new LlmException("overloaded_error", "busy", null);
            }
            return new LlmTurn(StopReason.TOOL_USE, "",
                    List.of(new ToolCall("toolu_1", tool.name(), java.util.Map.of("kind", kind, "fact", fact))),
                    300, 20, "tool_use");
        }

        @Override
        public LlmSession start(String systemPrompt, List<ToolSpec> tools) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeKnowledge implements KnowledgeSearch {

        volatile long count = 3;

        @Override
        public SearchResult search(long tenantId, Audience audience, String query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Document> findById(long tenantId, Audience audience, long documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SearchHit> recent(long tenantId, Audience audience, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count(long tenantId) {
            return count;
        }
    }
}

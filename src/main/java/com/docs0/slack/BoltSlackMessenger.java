package com.docs0.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.methods.response.conversations.ConversationsMembersResponse;
import com.slack.api.model.ConversationType;
import com.slack.api.model.block.LayoutBlock;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.LongConsumer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link SlackMessenger} on the Slack Web API client, using one workspace's bot token. Built by {@link SlackMessengers}. */
public class BoltSlackMessenger implements SlackMessenger {

    private static final int MEMBER_PAGE_SIZE = 200;
    private static final int MAX_MEMBER_PAGES = 50;

    /** Who the bot token belongs to. */
    public record Identity(String teamId, String team, String botName, String botUserId) {}

    static final int HISTORY_PAGE_SIZE = 200;
    static final int MAX_HISTORY_PAGES = 500;
    static final int MAX_RATE_LIMIT_RETRIES = 5;
    static final long MAX_RETRY_AFTER_SECONDS = 60;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 5;

    private final MethodsClient client;
    private final LongConsumer sleeper;
    private volatile String botUserId;
    private volatile Set<String> scopes;

    public BoltSlackMessenger(String token) {
        this(token == null ? "" : token, null, BoltSlackMessenger::sleepSeconds);
    }

    /** For tests: another API address (with a trailing slash) and a sleeper told how many seconds to wait. */
    BoltSlackMessenger(String token, String methodsPrefix, LongConsumer sleeper) {
        if (methodsPrefix == null) {
            this.client = Slack.getInstance().methods(token);
        } else {
            SlackConfig config = new SlackConfig();
            config.setMethodsEndpointUrlPrefix(methodsPrefix);
            this.client = Slack.getInstance(config).methods(token);
        }
        this.sleeper = sleeper;
    }

    @Override
    public String postMessage(String channel, String threadTs, String text, List<LayoutBlock> blocks) {
        var response = call("chat.postMessage", () -> client.chatPostMessage(r -> {
            r.channel(channel).text(text).unfurlLinks(false).unfurlMedia(false);
            if (threadTs != null) {
                r.threadTs(threadTs);
            }
            if (blocks != null && !blocks.isEmpty()) {
                r.blocks(blocks);
            }
            return r;
        }));
        return response.getTs();
    }

    @Override
    public void postEphemeral(String channel, String threadTs, String user, String text) {
        call("chat.postEphemeral", () -> client.chatPostEphemeral(r -> {
            r.channel(channel).user(user).text(text);
            if (threadTs != null) {
                r.threadTs(threadTs);
            }
            return r;
        }));
    }

    @Override
    public void updateMessage(String channel, String ts, String text, List<LayoutBlock> blocks) {
        call("chat.update", () -> client.chatUpdate(r -> {
            r.channel(channel).ts(ts).text(text);
            // chat.update has no unfurl options; links in an existing message are not unfurled again.
            if (blocks != null && !blocks.isEmpty()) {
                r.blocks(blocks);
            }
            return r;
        }));
    }

    @Override
    public Set<String> channelMembers(String channel) {
        Set<String> members = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_MEMBER_PAGES; page++) {
            String pageCursor = cursor;
            ConversationsMembersResponse response = call("conversations.members",
                    () -> client.conversationsMembers(r -> r.channel(channel).limit(MEMBER_PAGE_SIZE).cursor(pageCursor)));
            if (response.getMembers() != null) {
                members.addAll(response.getMembers());
            }
            cursor = response.getResponseMetadata() == null ? null : response.getResponseMetadata().getNextCursor();
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return members;
    }

    @Override
    public ChannelPrivacy channelPrivacy(String channel) {
        var info = call("conversations.info", () -> client.conversationsInfo(r -> r.channel(channel))).getChannel();
        if (info == null) {
            throw new SlackCallException("conversations.info", "no_channel", null);
        }
        return info.isPrivate() || info.isIm() || info.isMpim() ? ChannelPrivacy.PRIVATE : ChannelPrivacy.PUBLIC;
    }

    @Override
    public String userDisplayName(String userId) {
        var user = call("users.info", () -> client.usersInfo(r -> r.user(userId))).getUser();
        if (user == null) {
            return userId;
        }
        return nameOf(user, userId);
    }

    private static String nameOf(com.slack.api.model.User user, String fallback) {
        var profile = user.getProfile();
        for (String name : new String[] {profile == null ? null : profile.getDisplayName(),
                user.getRealName(), profile == null ? null : profile.getRealName(), user.getName()}) {
            if (name != null && !name.isBlank()) {
                return name.strip();
            }
        }
        return fallback;
    }

    @Override
    public List<ChannelInfo> listChannels() {
        List<ChannelInfo> channels = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_HISTORY_PAGES; page++) {
            String pageCursor = cursor;
            var response = call("conversations.list", () -> client.conversationsList(r -> r
                    .types(List.of(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                    .excludeArchived(true).limit(HISTORY_PAGE_SIZE).cursor(pageCursor)));
            if (response.getChannels() != null) {
                for (var c : response.getChannels()) {
                    if (c.getId() != null && !c.isArchived()) {
                        channels.add(new ChannelInfo(c.getId(), c.getName() == null ? c.getId() : c.getName(),
                                c.isPrivate(), c.isMember(), c.getNumOfMembers() == null ? 0 : c.getNumOfMembers()));
                    }
                }
            }
            cursor = nextCursor(response.getResponseMetadata());
            if (cursor == null) {
                break;
            }
        }
        failIfTruncated("conversations.list", cursor);
        return channels;
    }

    @Override
    public List<SlackMessage> history(String channel, Instant oldest) {
        String oldestTs = "%d.000000".formatted(oldest.getEpochSecond());
        List<SlackMessage> messages = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_HISTORY_PAGES; page++) {
            String pageCursor = cursor;
            var response = call("conversations.history", () -> client.conversationsHistory(r -> r
                    .channel(channel).oldest(oldestTs).limit(HISTORY_PAGE_SIZE).cursor(pageCursor)));
            if (response.getMessages() != null) {
                response.getMessages().forEach(m -> messages.add(message(m)));
            }
            cursor = response.isHasMore() ? nextCursor(response.getResponseMetadata()) : null;
            if (cursor == null) {
                break;
            }
        }
        failIfTruncated("conversations.history", cursor);
        return messages;
    }

    @Override
    public List<SlackMessage> replies(String channel, String threadTs) {
        List<SlackMessage> messages = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_HISTORY_PAGES; page++) {
            String pageCursor = cursor;
            var response = call("conversations.replies", () -> client.conversationsReplies(r -> r
                    .channel(channel).ts(threadTs).limit(HISTORY_PAGE_SIZE).cursor(pageCursor)));
            if (response.getMessages() != null) {
                response.getMessages().forEach(m -> messages.add(message(m)));
            }
            cursor = response.isHasMore() ? nextCursor(response.getResponseMetadata()) : null;
            if (cursor == null) {
                break;
            }
        }
        failIfTruncated("conversations.replies", cursor);
        return messages;
    }

    /** A listing cut short by the page limit must never be taken for the whole: whoever deletes by it would lose data. */
    private static void failIfTruncated(String method, String cursor) {
        if (cursor != null) {
            throw new SlackCallException(method, "too_many_pages", null);
        }
    }

    private static SlackMessage message(com.slack.api.model.Message m) {
        List<String> files = new ArrayList<>();
        if (m.getFiles() != null) {
            for (var f : m.getFiles()) {
                String title = f.getTitle() != null && !f.getTitle().isBlank() ? f.getTitle() : f.getName();
                if (title != null && !title.isBlank()) {
                    files.add(title.strip());
                }
            }
        }
        return new SlackMessage(m.getTs(), m.getThreadTs(), m.getUser(), m.getBotId(), m.getUsername(),
                m.getSubtype(), m.getText(), m.getReplyCount() == null ? 0 : m.getReplyCount(), m.getLatestReply(),
                m.getEdited() == null ? null : m.getEdited().getTs(), List.copyOf(files));
    }

    private static String nextCursor(com.slack.api.model.ResponseMetadata metadata) {
        String cursor = metadata == null ? null : metadata.getNextCursor();
        return cursor == null || cursor.isEmpty() ? null : cursor;
    }

    @Override
    public String permalink(String channel, String ts) {
        return call("chat.getPermalink", () -> client.chatGetPermalink(r -> r.channel(channel).messageTs(ts)))
                .getPermalink();
    }

    @Override
    public void joinChannel(String channel) {
        call("conversations.join", () -> client.conversationsJoin(r -> r.channel(channel)));
    }

    @Override
    public SlackUser userInfo(String userId) {
        var user = call("users.info", () -> client.usersInfo(r -> r.user(userId))).getUser();
        if (user == null) {
            throw new SlackCallException("users.info", "user_not_found", null);
        }
        String email = user.getProfile() == null ? null : user.getProfile().getEmail();
        return new SlackUser(userId, email == null || email.isBlank() ? null : email.strip(), nameOf(user, userId),
                user.isBot(), user.isDeleted());
    }

    @Override
    public boolean isWorkspaceAdmin(String userId) {
        var user = call("users.info", () -> client.usersInfo(r -> r.user(userId))).getUser();
        return user != null && !user.isDeleted() && !user.isBot()
                && (user.isAdmin() || user.isOwner() || user.isPrimaryOwner());
    }

    @Override
    public java.util.Optional<String> lookupByEmail(String email) {
        try {
            var user = call("users.lookupByEmail", () -> client.usersLookupByEmail(r -> r.email(email))).getUser();
            return user == null || user.getId() == null ? java.util.Optional.empty()
                    : java.util.Optional.of(user.getId());
        } catch (SlackCallException e) {
            if ("users_not_found".equals(e.code())) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public String openDm(String userId) {
        var channel = call("conversations.open", () -> client.conversationsOpen(r -> r.users(List.of(userId))))
                .getChannel();
        if (channel == null || channel.getId() == null) {
            throw new SlackCallException("conversations.open", "no_channel", null);
        }
        return channel.getId();
    }

    @Override
    public Set<String> botScopes() {
        Set<String> known = scopes;
        if (known == null) {
            try {
                identify();
            } catch (RuntimeException e) {
                return Set.of();
            }
            known = scopes;
        }
        return known == null ? Set.of() : known;
    }

    @Override
    public String botUserId() {
        String id = botUserId;
        return id != null ? id : identify().botUserId();
    }

    /** Asks Slack who the bot token belongs to and remembers the bot's user id. */
    public Identity identify() {
        AuthTestResponse response = call("auth.test", () -> client.authTest(r -> r));
        botUserId = response.getUserId();
        // Slack lists the token's scopes in a response header.
        var header = response.getHttpResponseHeaders() == null ? null
                : response.getHttpResponseHeaders().entrySet().stream()
                        .filter(e -> "x-oauth-scopes".equalsIgnoreCase(e.getKey())).findFirst().orElse(null);
        if (header != null && header.getValue() != null) {
            Set<String> granted = new java.util.HashSet<>();
            for (String value : header.getValue()) {
                for (String scope : value.split(",")) {
                    if (!scope.isBlank()) {
                        granted.add(scope.strip());
                    }
                }
            }
            scopes = Set.copyOf(granted);
        }
        return new Identity(response.getTeamId(), response.getTeam(), response.getUser(), response.getUserId());
    }

    private interface SlackCall<T> {
        T run() throws IOException, SlackApiException;
    }

    /** One Slack call. A rate limit (HTTP 429) is waited out as Slack asks (Retry-After), a few times. */
    private <T extends SlackApiTextResponse> T call(String method, SlackCall<T> call) {
        for (int attempt = 0;; attempt++) {
            try {
                return callOnce(method, call);
            } catch (SlackCallException e) {
                if (!(e.getCause() instanceof SlackApiException api) || api.getResponse() == null
                        || api.getResponse().code() != 429 || attempt >= MAX_RATE_LIMIT_RETRIES) {
                    throw e;
                }
                sleeper.accept(retryAfterSeconds(api));
            }
        }
    }

    private static long retryAfterSeconds(SlackApiException e) {
        String header = e.getResponse().header("Retry-After");
        long seconds = DEFAULT_RETRY_AFTER_SECONDS;
        try {
            if (header != null) {
                seconds = Long.parseLong(header.strip());
            }
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        return Math.max(0, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T extends SlackApiTextResponse> T callOnce(String method, SlackCall<T> call) {
        T response;
        try {
            response = call.run();
        } catch (SlackApiException e) {
            String code = e.getError() != null && e.getError().getError() != null
                    ? e.getError().getError()
                    : "http_error";
            throw new SlackCallException(method, code, e);
        } catch (IOException e) {
            throw new SlackCallException(method, "network_error", e);
        }
        if (!response.isOk()) {
            throw new SlackCallException(method, response.getError() == null ? "unknown_error" : response.getError(), null);
        }
        return response;
    }
}

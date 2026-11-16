package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.methods.response.conversations.ConversationsMembersResponse;
import com.slack.api.model.block.LayoutBlock;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** {@link SlackMessenger} on the Slack Web API client, using the bot token. */
@Component
public class BoltSlackMessenger implements SlackMessenger {

    private static final int MEMBER_PAGE_SIZE = 200;
    private static final int MAX_MEMBER_PAGES = 50;

    /** Who the bot token belongs to. */
    public record Identity(String team, String botName, String botUserId) {}

    private final MethodsClient client;
    private volatile String botUserId;

    public BoltSlackMessenger(Docs0Properties props) {
        String token = props.slack() == null || props.slack().botToken() == null ? "" : props.slack().botToken();
        this.client = Slack.getInstance().methods(token);
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
    public String userDisplayName(String userId) {
        var user = call("users.info", () -> client.usersInfo(r -> r.user(userId))).getUser();
        if (user == null) {
            return userId;
        }
        var profile = user.getProfile();
        for (String name : new String[] {profile == null ? null : profile.getDisplayName(),
                user.getRealName(), profile == null ? null : profile.getRealName(), user.getName()}) {
            if (name != null && !name.isBlank()) {
                return name.strip();
            }
        }
        return userId;
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
        return new Identity(response.getTeam(), response.getUser(), response.getUserId());
    }

    private interface SlackCall<T> {
        T run() throws IOException, SlackApiException;
    }

    private static <T extends SlackApiTextResponse> T call(String method, SlackCall<T> call) {
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

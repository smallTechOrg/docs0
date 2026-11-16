package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.web.StatusContributor;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageChannelJoinEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import com.slack.api.model.event.ReactionAddedEvent;
import com.slack.api.socket_mode.SocketModeClient;
import jakarta.annotation.PreDestroy;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Connects docs0 to Slack over Socket Mode and passes messages to {@link QuestionHandler}.
 * Every Slack event is acknowledged at once; answering happens on other threads.
 * Never blocks or fails application startup.
 */
@Component
public class SlackGateway implements StatusContributor {

    static final String NAME = "Slack";

    private static final Logger log = LoggerFactory.getLogger(SlackGateway.class);
    private static final Pattern FOLLOW_UP_ACTION =
            Pattern.compile("^" + Pattern.quote(AnswerRenderer.FOLLOW_UP_ACTION_PREFIX) + "\\d+$");
    private static final long RETRY_DELAY_MILLIS = 30_000;

    private final Docs0Properties props;
    private final BoltSlackMessenger messenger;
    private final QuestionHandler handler;

    private volatile ComponentStatus status;
    private volatile SocketModeApp socketModeApp;
    private volatile Thread connector;
    private volatile boolean stopped;

    public SlackGateway(Docs0Properties props, BoltSlackMessenger messenger, QuestionHandler handler) {
        this.props = props;
        this.messenger = messenger;
        this.handler = handler;
        this.status = configured()
                ? ComponentStatus.down(NAME, "not connected yet")
                : ComponentStatus.off(NAME, "no Slack tokens configured");
    }

    @Override
    public ComponentStatus status() {
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!configured() || connector != null) {
            return;
        }
        connector = Thread.ofPlatform().name("slack-gateway").daemon(true).start(this::connectUntilUp);
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        Thread thread = connector;
        if (thread != null) {
            thread.interrupt();
        }
        SocketModeApp app = socketModeApp;
        if (app != null) {
            try {
                app.close();
            } catch (Exception e) {
                log.debug("Slack connection did not close cleanly: {}", e.getClass().getName());
            }
        }
    }

    private boolean configured() {
        return props.slack() != null && props.slack().configured();
    }

    /** Connects, retrying on network trouble. Gives up when Slack rejects the tokens. */
    private void connectUntilUp() {
        while (!stopped) {
            try {
                connect();
                return;
            } catch (Exception e) {
                String reason = failureReason(e);
                status = ComponentStatus.down(NAME, reason);
                boolean retry = isNetworkTrouble(e);
                log.warn("Slack connection failed: {}{}", reason, retry ? "; retrying in 30 seconds" : "");
                if (!retry) {
                    return;
                }
            }
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void connect() throws Exception {
        BoltSlackMessenger.Identity identity = messenger.identify();

        App app = new App(AppConfig.builder().singleTeamBotToken(props.slack().botToken()).build());
        register(app);

        SocketModeApp socket = new SocketModeApp(props.slack().appToken(), SocketModeClient.Backend.JavaWebSocket, app);
        socket.startAsync();
        socketModeApp = socket;
        if (stopped) {
            socket.close();
            return;
        }
        status = ComponentStatus.up(NAME, "connected to " + identity.team() + " as @" + identity.botName());
        log.info("Slack connected: bot user {}", identity.botUserId());
    }

    private void register(App app) {
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            dispatch(() -> handler.handle(fromMention(payload.getEvent(), payload.getEventId())));
            return ctx.ack();
        });
        app.event(MessageEvent.class, (payload, ctx) -> {
            dispatch(() -> handler.handle(fromMessage(payload.getEvent(), payload.getEventId())));
            return ctx.ack();
        });
        app.blockAction(FOLLOW_UP_ACTION, (request, ctx) -> {
            dispatch(() -> followUp(request.getPayload()));
            return ctx.ack();
        });

        // Not questions: bot messages, edits, deletions, joins, file uploads, broadcasts.
        // Acknowledged here so Bolt does not report them as unhandled.
        app.event(MessageBotEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageChangedEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageDeletedEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageChannelJoinEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageFileShareEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageThreadBroadcastEvent.class, (payload, ctx) -> ctx.ack());
        // Subscribed for feedback in a later phase.
        app.event(ReactionAddedEvent.class, (payload, ctx) -> ctx.ack());
    }

    /** Hands an event to the handler without ever failing the acknowledgement. */
    private void dispatch(Runnable handoff) {
        try {
            handoff.run();
        } catch (Exception e) {
            log.warn("Slack event could not be queued: {}", e.getClass().getName());
        }
    }

    private void followUp(BlockActionPayload payload) {
        if (payload == null || payload.getActions() == null || payload.getActions().isEmpty()
                || payload.getUser() == null) {
            return;
        }
        BlockActionPayload.Action action = payload.getActions().get(0);
        BlockActionPayload.Container container = payload.getContainer();
        String channel = payload.getChannel() != null ? payload.getChannel().getId()
                : container != null ? container.getChannelId() : null;
        String messageTs = payload.getMessage() != null ? payload.getMessage().getTs()
                : container != null ? container.getMessageTs() : null;
        String threadTs = payload.getMessage() != null && payload.getMessage().getThreadTs() != null
                ? payload.getMessage().getThreadTs()
                : container != null ? container.getThreadTs() : null;
        handler.followUp(channel, followUpThread(channel, threadTs, messageTs), payload.getUser().getId(),
                action.getValue(), payload.getUser().getId() + ":" + messageTs + ":" + action.getActionTs());
    }

    // ---- mapping Slack payloads to handler events (pure) --------------------------------------

    /** An app_mention. Null when it is not a question: an edit, or a DM (the message event covers DMs). */
    static IncomingEvent fromMention(AppMentionEvent e, String eventId) {
        if (e == null || e.getEdited() != null || QuestionHandler.isDmChannel(e.getChannel())) {
            return null;
        }
        return new IncomingEvent(IncomingEvent.Kind.MENTION, e.getChannel(), e.getUser(), e.getText(), e.getTs(),
                e.getThreadTs(), eventId, e.getSubtype(), e.getBotId() != null);
    }

    /**
     * A plain message event. A DM is always a candidate; in channels only thread replies
     * are (top-level channel messages matter only as mentions). Null otherwise.
     */
    static IncomingEvent fromMessage(MessageEvent e, String eventId) {
        if (e == null || e.getEdited() != null) {
            return null;
        }
        IncomingEvent.Kind kind;
        if ("im".equals(e.getChannelType()) || QuestionHandler.isDmChannel(e.getChannel())) {
            kind = IncomingEvent.Kind.DIRECT_MESSAGE;
        } else if (e.getThreadTs() != null && !e.getThreadTs().equals(e.getTs())) {
            kind = IncomingEvent.Kind.THREAD_REPLY;
        } else {
            return null;
        }
        return new IncomingEvent(kind, e.getChannel(), e.getUser(), e.getText(), e.getTs(), e.getThreadTs(), eventId,
                null, e.getBotId() != null);
    }

    /** Where a follow-up is asked: the clicked message's thread; in a channel never outside a thread. */
    static String followUpThread(String channel, String threadTs, String messageTs) {
        if (threadTs != null) {
            return threadTs;
        }
        return QuestionHandler.isDmChannel(channel) ? null : messageTs;
    }

    private static boolean isNetworkTrouble(Exception e) {
        return !(e instanceof SlackMessenger.SlackCallException slack) || slack.code().equals("network_error");
    }

    /** A short reason for the status page. Never includes a token. */
    private static String failureReason(Exception e) {
        if (e instanceof SlackMessenger.SlackCallException slack) {
            return slack.code().equals("network_error")
                    ? "could not reach Slack"
                    : "Slack rejected the bot token (" + slack.code() + ")";
        }
        return "could not open the Socket Mode connection (" + e.getClass().getSimpleName() + ")";
    }
}

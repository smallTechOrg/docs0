package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.tenant.local.LocalSlackPilot;
import com.docs0.web.StatusContributor;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.AppUninstalledEvent;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageChannelJoinEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import com.slack.api.model.event.ReactionAddedEvent;
import com.slack.api.model.event.ReactionRemovedEvent;
import com.slack.api.model.event.TokensRevokedEvent;
import com.slack.api.socket_mode.SocketModeClient;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.OptionalLong;
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
 *
 * <p>Socket Mode serves the one Slack app configured in DOCS0_SLACK_*_TOKEN (the pilot workspace). Whatever
 * workspace an event comes from, its tenant is looked up in {@link SlackWorkspaces} by team id; an event from a
 * workspace nobody registered is ignored.
 */
@Component
public class SlackGateway implements StatusContributor {

    static final String NAME = "Slack";

    private static final Logger log = LoggerFactory.getLogger(SlackGateway.class);
    private static final Pattern FOLLOW_UP_ACTION =
            Pattern.compile("^" + Pattern.quote(AnswerRenderer.FOLLOW_UP_ACTION_PREFIX) + "\\d+$");
    private static final Pattern DRAFT_ACTION = Pattern.compile("^(?:"
            + Pattern.quote(AnswerRenderer.DRAFT_CREATE_ACTION) + "|"
            + Pattern.quote(AnswerRenderer.DRAFT_CANCEL_ACTION) + ")$");
    private static final long RETRY_DELAY_MILLIS = 30_000;

    private final Docs0Properties props;
    private final SlackWorkspaces workspaces;
    private final LocalSlackPilot pilot;
    private final QuestionHandler handler;
    private final ReactionHandler reactions;
    private final ActionHandler draftActions;

    private volatile ComponentStatus status;
    private volatile SocketModeApp socketModeApp;
    private volatile Thread connector;
    private volatile boolean stopped;

    public SlackGateway(Docs0Properties props, SlackWorkspaces workspaces, LocalSlackPilot pilot,
            QuestionHandler handler, ReactionHandler reactions, ActionHandler draftActions) {
        this.props = props;
        this.workspaces = workspaces;
        this.pilot = pilot;
        this.handler = handler;
        this.reactions = reactions;
        this.draftActions = draftActions;
        this.status = configured()
                ? ComponentStatus.down(NAME, "not connected yet")
                : ComponentStatus.off(NAME, "no Slack tokens configured");
    }

    /** The Socket Mode connection serves the pilot tenant only; every other tenant has no connection to report. */
    @Override
    public ComponentStatus status(long tenantId) {
        boolean socketServes = configured() && pilot != null && tenantId == pilot.tenantId();
        if (props.multiTenant() && workspaces != null && !socketServes) {
            return workspaces.forTenant(tenantId)
                    .map(w -> ComponentStatus.up(NAME, "connected to " + w.teamName() + " (events over HTTP)"))
                    .orElseGet(() -> ComponentStatus.off(NAME, "docs0 is not installed in a Slack workspace"));
        }
        if (pilot != null && tenantId != pilot.tenantId()) {
            return ComponentStatus.off(NAME, "not connected: Socket Mode serves only the configured workspace");
        }
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
        // The workspace is registered for the configured tenant before any event is accepted.
        BoltSlackMessenger.Identity identity = pilot.register();

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

    /**
     * Socket Mode: Bolt delivers each payload, and every handler passes it to the same methods the HTTP endpoint
     * uses ({@link #appMention} and friends), so the transport is the only difference.
     */
    private void register(App app) {
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            appMention(payload.getTeamId(), payload.getEvent(), payload.getEventId());
            return ctx.ack();
        });
        app.event(MessageEvent.class, (payload, ctx) -> {
            message(payload.getTeamId(), payload.getEvent(), payload.getEventId());
            return ctx.ack();
        });
        app.blockAction(FOLLOW_UP_ACTION, (request, ctx) -> {
            blockAction(request.getPayload());
            return ctx.ack();
        });
        app.blockAction(DRAFT_ACTION, (request, ctx) -> {
            blockAction(request.getPayload());
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
        // The company pilot has one fixed workspace: removing the app there ends the connection by itself. The
        // distributed app handles these over HTTP (SlackHttpEvents).
        app.event(AppUninstalledEvent.class, (payload, ctx) -> ctx.ack());
        app.event(TokensRevokedEvent.class, (payload, ctx) -> ctx.ack());
        // Thumbs up and down on answers are feedback.
        app.event(ReactionAddedEvent.class, (payload, ctx) -> {
            reactionAdded(payload.getTeamId(), payload.getEvent());
            return ctx.ack();
        });
        app.event(ReactionRemovedEvent.class, (payload, ctx) -> {
            reactionRemoved(payload.getTeamId(), payload.getEvent());
            return ctx.ack();
        });
    }

    // ---- the transport-independent entry points: Socket Mode and HTTP both end here ---------------------------

    void appMention(String teamId, AppMentionEvent event, String eventId) {
        dispatch(() -> incoming(teamId, fromMention(event, eventId)));
    }

    /** A message event without a subtype (a person's message). Subtyped messages are not questions. */
    void message(String teamId, MessageEvent event, String eventId) {
        dispatch(() -> incoming(teamId, fromMessage(event, eventId)));
    }

    void reactionAdded(String teamId, ReactionAddedEvent event) {
        dispatch(() -> reaction(teamId, fromReactionAdded(event)));
    }

    void reactionRemoved(String teamId, ReactionRemovedEvent event) {
        dispatch(() -> reaction(teamId, fromReactionRemoved(event)));
    }

    /** A click on a button docs0 posted: a follow-up question, or Create / Cancel under a draft. Others are ignored. */
    void blockAction(BlockActionPayload payload) {
        String actionId = payload == null || payload.getActions() == null || payload.getActions().isEmpty() ? null
                : payload.getActions().get(0).getActionId();
        if (actionId != null && FOLLOW_UP_ACTION.matcher(actionId).matches()) {
            dispatch(() -> followUp(payload));
        } else if (actionId != null && DRAFT_ACTION.matcher(actionId).matches()) {
            dispatch(() -> draftClick(payload));
        }
    }

    /**
     * The tenant of a Slack workspace. Empty, with only the workspace id logged, for a workspace docs0 does not serve:
     * its events are dropped, never answered from some other tenant's knowledge.
     */
    OptionalLong tenantOf(String teamId) {
        Optional<SlackWorkspaces.Installation> installation = workspaces.byTeamId(teamId);
        if (installation.isEmpty()) {
            log.info("Slack event from an unknown workspace ignored: team={}", teamId);
            return OptionalLong.empty();
        }
        return OptionalLong.of(installation.get().tenantId());
    }

    private void incoming(String teamId, IncomingEvent event) {
        if (event != null) {
            tenantOf(teamId).ifPresent(tenantId -> handler.handle(tenantId, event.withTeam(teamId)));
        }
    }

    private void reaction(String teamId, ReactionEvent event) {
        if (event != null) {
            tenantOf(teamId).ifPresent(tenantId -> reactions.handle(tenantId, event.withTeam(teamId)));
        }
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
        tenantOf(teamOf(payload)).ifPresent(tenantId -> handler.followUp(tenantId, channel,
                followUpThread(channel, threadTs, messageTs), payload.getUser().getId(), action.getValue(),
                payload.getUser().getId() + ":" + messageTs + ":" + action.getActionTs()));
    }

    /** A click on Create or Cancel under a draft. */
    private void draftClick(BlockActionPayload payload) {
        DraftClick click = fromDraftClick(payload);
        if (click != null) {
            tenantOf(teamOf(payload)).ifPresent(tenantId -> draftActions.handle(tenantId, click.channel(),
                    click.messageTs(), click.threadTs(), click.user(), click.actionId(), click.value()));
        }
    }

    /** The workspace a button click came from. */
    static String teamOf(BlockActionPayload payload) {
        if (payload.getTeam() != null && payload.getTeam().getId() != null) {
            return payload.getTeam().getId();
        }
        return payload.getUser() == null ? null : payload.getUser().getTeamId();
    }

    /** What a click on a draft's button carries. {@code threadTs} is null for an unthreaded DM. */
    record DraftClick(String channel, String messageTs, String threadTs, String user, String actionId, String value) {}

    /** Reads a button click payload. Null when it carries no action or no user. */
    static DraftClick fromDraftClick(BlockActionPayload payload) {
        if (payload == null || payload.getActions() == null || payload.getActions().isEmpty()
                || payload.getUser() == null) {
            return null;
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
        return new DraftClick(channel, messageTs, followUpThread(channel, threadTs, messageTs),
                payload.getUser().getId(), action.getActionId(), action.getValue());
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
     * A plain message event. A DM is always a candidate; in channels thread replies are, and so are
     * top-level messages (docs0 only ever looks at those in channels an admin opted in, and mentions are
     * left to the mention event). Null otherwise, including group DMs.
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
        } else if (e.getThreadTs() == null
                && ("channel".equals(e.getChannelType()) || "group".equals(e.getChannelType()))) {
            kind = IncomingEvent.Kind.CHANNEL_MESSAGE;
        } else {
            return null;
        }
        return new IncomingEvent(kind, e.getChannel(), e.getUser(), e.getText(), e.getTs(), e.getThreadTs(), eventId,
                e.getSubtype(), e.getBotId() != null);
    }

    /** A reaction added to a message. Null when it was not added to a message. */
    static ReactionEvent fromReactionAdded(ReactionAddedEvent e) {
        if (e == null || e.getItem() == null || !"message".equals(e.getItem().getType())) {
            return null;
        }
        return new ReactionEvent(true, e.getItem().getChannel(), e.getUser(), e.getReaction(), e.getItem().getTs());
    }

    /** A reaction removed from a message. Null when it was not removed from a message. */
    static ReactionEvent fromReactionRemoved(ReactionRemovedEvent e) {
        if (e == null || e.getItem() == null || !"message".equals(e.getItem().getType())) {
            return null;
        }
        return new ReactionEvent(false, e.getItem().getChannel(), e.getUser(), e.getReaction(), e.getItem().getTs());
    }

    /** Where a follow-up is asked: the clicked message's thread; in a channel never outside a thread. */
    static String followUpThread(String channel, String threadTs, String messageTs) {
        if (threadTs != null) {
            return threadTs;
        }
        return QuestionHandler.isDmChannel(channel) ? null : messageTs;
    }

    private static boolean isNetworkTrouble(Exception e) {
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return false; // the workspace cannot be stored (for example no DOCS0_ENCRYPTION_KEY): retrying cannot help
        }
        return !(e instanceof SlackMessenger.SlackCallException slack) || slack.code().equals("network_error");
    }

    /** A short reason for the status page. Never includes a token. */
    private static String failureReason(Exception e) {
        if (e instanceof SlackMessenger.SlackCallException slack) {
            return slack.code().equals("network_error")
                    ? "could not reach Slack"
                    : "Slack rejected the bot token (" + slack.code() + ")";
        }
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return "could not store the Slack workspace (is DOCS0_ENCRYPTION_KEY set?)";
        }
        return "could not open the Socket Mode connection (" + e.getClass().getSimpleName() + ")";
    }
}

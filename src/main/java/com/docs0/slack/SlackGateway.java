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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

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

    /** On-demand registration of the pilot workspace (http transport): single-flight, remembered, retried every 30 s. */
    private final Object registration = new Object();
    private volatile boolean pilotRegistered;
    private long lastFailureNanos;
    private boolean failedBefore;
    private java.util.function.LongSupplier nanoClock = System::nanoTime;

    public SlackGateway(Docs0Properties props, SlackWorkspaces workspaces, LocalSlackPilot pilot,
            QuestionHandler handler, ReactionHandler reactions, ActionHandler draftActions) {
        this.props = props;
        this.workspaces = workspaces;
        this.pilot = pilot;
        this.handler = handler;
        this.reactions = reactions;
        this.draftActions = draftActions;
        if (props.slack() != null && props.slack().httpTransport()) {
            this.status = props.slack().httpPilotConfigured()
                    ? ComponentStatus.up(NAME, "events over HTTP (signing secret set)")
                    : ComponentStatus.off(NAME, "events over HTTP need DOCS0_SLACK_BOT_TOKEN and DOCS0_SLACK_SIGNING_SECRET");
        } else {
            this.status = configured()
                    ? ComponentStatus.down(NAME, "not connected yet")
                    : ComponentStatus.off(NAME, "no Slack tokens configured");
        }
    }

    /** The Socket Mode connection serves the pilot tenant only; every other tenant has no connection to report. */
    @Override
    public ComponentStatus status(long tenantId) {
        boolean http = props.slack() != null && props.slack().httpTransport();
        boolean socketServes = !http && configured() && pilot != null && tenantId == pilot.tenantId();
        boolean httpServes = http && props.slack().httpPilotConfigured() && pilot != null
                && tenantId == pilot.tenantId();
        if (httpServes) {
            return status;
        }
        if (props.multiTenant() && workspaces != null && !socketServes && !httpServes) {
            return workspaces.forTenant(tenantId)
                    .map(w -> ComponentStatus.up(NAME, "connected to " + w.teamName() + " (events over HTTP)"))
                    .orElseGet(() -> ComponentStatus.off(NAME, "docs0 is not installed in a Slack workspace"));
        }
        if (pilot != null && tenantId != pilot.tenantId()) {
            return ComponentStatus.off(NAME, http
                    ? "not connected: only the configured workspace is served"
                    : "not connected: Socket Mode serves only the configured workspace");
        }
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (connector != null) {
            return;
        }
        if (props.slack() != null && props.slack().httpTransport()) {
            // No Socket Mode over HTTP. The workspace is registered in the background now, and on demand by the
            // first event if that event arrives while this is still running (see tenantOf).
            if (props.slack().httpPilotConfigured() && pilot != null) {
                connector = Thread.ofPlatform().name("slack-gateway").daemon(true).start(this::registerUntilUp);
            }
            return;
        }
        if (!configured() || (props.slack() != null && !props.slack().socketTransport())) {
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

    /** HTTP transport: registers the pilot workspace, retrying on network trouble like Socket Mode does. */
    private void registerUntilUp() {
        while (!stopped && !pilotRegistered) {
            synchronized (registration) {
                if (!pilotRegistered) {
                    failedBefore = false; // the startup loop paces its own retries
                    registerPilotLocked();
                }
            }
            if (pilotRegistered) {
                return;
            }
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Registers the pilot workspace. Callers hold {@link #registration}. Never throws; sets the status. */
    private void registerPilotLocked() {
        try {
            BoltSlackMessenger.Identity identity = pilot.register();
            pilotRegistered = true;
            status = ComponentStatus.up(NAME, "events over HTTP (signing secret set)");
            log.info("Slack workspace registered for HTTP events: bot user {}", identity.botUserId());
        } catch (Exception e) {
            failedBefore = true;
            lastFailureNanos = nanoClock.getAsLong();
            status = ComponentStatus.down(NAME, failureReason(e));
            log.warn("Slack workspace could not be registered: {}", failureReason(e));
        }
    }

    /**
     * An event from a workspace that is not registered yet, in HTTP transport with the pilot's bot token set: the
     * first message often arrives while the instance is still starting, so the pilot workspace is registered now,
     * in this thread, instead of dropping the event. One caller registers while the others wait for its result;
     * after a failure nobody tries again for 30 seconds. True when the workspace may be registered by now.
     */
    private boolean registerPilotOnDemand() {
        Docs0Properties.Slack slack = props.slack();
        if (slack == null || !slack.httpPilotConfigured() || pilot == null) {
            return false;
        }
        synchronized (registration) {
            if (pilotRegistered) {
                return true;
            }
            if (failedBefore && nanoClock.getAsLong() - lastFailureNanos < TimeUnit.MILLISECONDS.toNanos(RETRY_DELAY_MILLIS)) {
                return false;
            }
            registerPilotLocked();
            return pilotRegistered;
        }
    }

    /** For tests: the clock the 30-second retry spacing uses. */
    void useNanoClock(java.util.function.LongSupplier clock) {
        this.nanoClock = clock;
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

    CompletableFuture<Void> appMention(String teamId, AppMentionEvent event, String eventId) {
        return dispatch(() -> incoming(teamId, fromMention(event, eventId)));
    }

    /** A message event without a subtype (a person's message). Subtyped messages are not questions. */
    CompletableFuture<Void> message(String teamId, MessageEvent event, String eventId) {
        return dispatch(() -> incoming(teamId, fromMessage(event, eventId)));
    }

    CompletableFuture<Void> reactionAdded(String teamId, ReactionAddedEvent event) {
        return dispatch(() -> reaction(teamId, fromReactionAdded(event)));
    }

    CompletableFuture<Void> reactionRemoved(String teamId, ReactionRemovedEvent event) {
        return dispatch(() -> reaction(teamId, fromReactionRemoved(event)));
    }

    /** A click on a button docs0 posted: a follow-up question, or Create / Cancel under a draft. Others are ignored. */
    CompletableFuture<Void> blockAction(BlockActionPayload payload) {
        String actionId = payload == null || payload.getActions() == null || payload.getActions().isEmpty() ? null
                : payload.getActions().get(0).getActionId();
        if (actionId != null && FOLLOW_UP_ACTION.matcher(actionId).matches()) {
            return dispatch(() -> followUp(payload));
        } else if (actionId != null && DRAFT_ACTION.matcher(actionId).matches()) {
            return dispatch(() -> draftClick(payload));
        }
        return DONE;
    }

    /**
     * The tenant of a Slack workspace. Empty, with only the workspace id logged, for a workspace docs0 does not serve:
     * its events are dropped, never answered from some other tenant's knowledge.
     */
    OptionalLong tenantOf(String teamId) {
        Optional<SlackWorkspaces.Installation> installation = workspaces.byTeamId(teamId);
        if (installation.isEmpty() && teamId != null && !teamId.isBlank() && registerPilotOnDemand()) {
            installation = workspaces.byTeamId(teamId);
        }
        if (installation.isEmpty()) {
            log.info("Slack event from an unknown workspace ignored: team={}", teamId);
            return OptionalLong.empty();
        }
        return OptionalLong.of(installation.get().tenantId());
    }

    private CompletableFuture<Void> incoming(String teamId, IncomingEvent event) {
        if (event == null) {
            return DONE;
        }
        OptionalLong tenantId = tenantOf(teamId);
        return tenantId.isEmpty() ? DONE : handler.handle(tenantId.getAsLong(), event.withTeam(teamId));
    }

    private CompletableFuture<Void> reaction(String teamId, ReactionEvent event) {
        if (event == null) {
            return DONE;
        }
        OptionalLong tenantId = tenantOf(teamId);
        return tenantId.isEmpty() ? DONE : reactions.handle(tenantId.getAsLong(), event.withTeam(teamId));
    }

    /**
     * Hands an event to the handler without ever failing the acknowledgement. The returned future completes when the
     * handler has finished with the event; HTTP transport waits for it before answering Slack.
     */
    private CompletableFuture<Void> dispatch(java.util.function.Supplier<CompletableFuture<Void>> handoff) {
        try {
            CompletableFuture<Void> work = handoff.get();
            return work == null ? DONE : work;
        } catch (Exception e) {
            log.warn("Slack event could not be queued: {}", e.getClass().getName());
            return DONE;
        }
    }

    private CompletableFuture<Void> followUp(BlockActionPayload payload) {
        if (payload == null || payload.getActions() == null || payload.getActions().isEmpty()
                || payload.getUser() == null) {
            return DONE;
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
        OptionalLong tenantId = tenantOf(teamOf(payload));
        return tenantId.isEmpty() ? DONE : handler.followUp(tenantId.getAsLong(), channel,
                followUpThread(channel, threadTs, messageTs), payload.getUser().getId(), action.getValue(),
                payload.getUser().getId() + ":" + messageTs + ":" + action.getActionTs());
    }

    /** A click on Create or Cancel under a draft. */
    private CompletableFuture<Void> draftClick(BlockActionPayload payload) {
        DraftClick click = fromDraftClick(payload);
        if (click == null) {
            return DONE;
        }
        OptionalLong tenantId = tenantOf(teamOf(payload));
        return tenantId.isEmpty() ? DONE : draftActions.handle(tenantId.getAsLong(), click.channel(),
                click.messageTs(), click.threadTs(), click.user(), click.actionId(), click.value());
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

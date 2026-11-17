package com.docs0.slack;

import com.docs0.action.PendingAction;
import com.docs0.action.PendingActionRepository;
import com.docs0.agent.Agent;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.BudgetService;
import com.docs0.budget.UsageLog;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.stale.StaleDocs;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides whether a Slack message is a question for docs0 and, if so, answers it: status
 * line, agent call, rendered answer, interaction log. Returns immediately; the work runs on
 * a virtual thread, one question at a time per conversation.
 */
@Component
public class QuestionHandler {

    static final String STATUS_SEARCHING = "Searching your docs…";
    static final String HINT =
            "Hi! Ask me a question about your company's docs, for example: _What is our refund policy?_";
    static final String NOT_CONNECTED =
            "I'm not connected to my AI model yet, so I can't answer questions. Please ask your docs0 admin to finish the setup.";
    static final String OVER_BUDGET =
            "This month's docs0 budget is used up, so I can't answer new questions until next month. Please ask your docs0 admin if you need more.";
    static final String NO_SOURCES =
            "No sources are synced yet, so I have nothing to search. Please ask your docs0 admin to connect one.";
    static final String SUSPENDED =
            "docs0 is paused for your workspace, so I can't answer questions right now. Please ask your docs0 admin.";
    static final String APOLOGY = "Sorry, something went wrong while I was working on that. Please try asking again.";

    static final Duration STATUS_INTERVAL = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(QuestionHandler.class);
    private static final Pattern ANY_LEADING_MENTION = Pattern.compile("^\\s*<@[A-Z0-9]+(?:\\|[^>]*)?>");
    private static final int MAX_STATUS_CHARS = 300;
    private static final int MAX_ERROR_CHARS = 1000;
    private static final int DEDUPE_CAPACITY = 5000;
    private static final long PRIVACY_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int PRIVACY_CAPACITY = 5000;
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /** Only asks whether a tenant is active: which tenant an event belongs to is decided by the caller. */
    private final TenantService tenants;
    private final Agent agent;
    private final KnowledgeSearch knowledge;
    private final BudgetService budget;
    private final ConversationRepository conversations;
    private final InteractionRepository interactions;
    private final SlackMessengers messengers;
    private final CorrectionFlow corrections;
    private final PendingActionRepository pendingActions;
    private final IdentityService identities;
    /** Unprompted answers, stale-document notes and the cost of side calls. Null: the feature is off. */
    private final ProactiveResponder proactive;
    private final StaleDocs staleDocs;
    private final UsageLog usage;
    private final long statusIntervalMillis;

    /** Whether a channel is private, per tenant and channel, remembered for a few minutes. */
    private final Map<String, CachedPrivacy> privacy = new ConcurrentHashMap<>();

    private record CachedPrivacy(SlackMessenger.ChannelPrivacy value, long expiresAtNanos) {}

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService statusTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("slack-status-timer").daemon(true).factory());
    /** The last queued task of each conversation that still has work in flight. */
    private final Map<String, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();
    /** Recently seen event ids and message ids, so a redelivered event is answered once. */
    private final Set<String> seen = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUPE_CAPACITY;
                }
            }));

    @Autowired
    public QuestionHandler(TenantService tenants, Agent agent, KnowledgeSearch knowledge, BudgetService budget,
            ConversationRepository conversations, InteractionRepository interactions, SlackMessengers messengers,
            CorrectionFlow corrections, PendingActionRepository pendingActions, IdentityService identities,
            ProactiveResponder proactive, StaleDocs staleDocs, UsageLog usage) {
        this(tenants, agent, knowledge, budget, conversations, interactions, messengers, corrections, pendingActions,
                identities, proactive, staleDocs, usage, STATUS_INTERVAL);
    }

    /** Without unprompted answers and stale-document notes. */
    public QuestionHandler(TenantService tenants, Agent agent, KnowledgeSearch knowledge, BudgetService budget,
            ConversationRepository conversations, InteractionRepository interactions, SlackMessengers messengers,
            CorrectionFlow corrections, PendingActionRepository pendingActions, IdentityService identities) {
        this(tenants, agent, knowledge, budget, conversations, interactions, messengers, corrections, pendingActions,
                identities, null, null, null, STATUS_INTERVAL);
    }

    /** For tests: {@code statusInterval} is the minimum time between two status line updates. */
    QuestionHandler(TenantService tenants, Agent agent, KnowledgeSearch knowledge, BudgetService budget,
            ConversationRepository conversations, InteractionRepository interactions, SlackMessengers messengers,
            CorrectionFlow corrections, PendingActionRepository pendingActions, IdentityService identities,
            Duration statusInterval) {
        this(tenants, agent, knowledge, budget, conversations, interactions, messengers, corrections, pendingActions,
                identities, null, null, null, statusInterval);
    }

    QuestionHandler(TenantService tenants, Agent agent, KnowledgeSearch knowledge, BudgetService budget,
            ConversationRepository conversations, InteractionRepository interactions, SlackMessengers messengers,
            CorrectionFlow corrections, PendingActionRepository pendingActions, IdentityService identities,
            ProactiveResponder proactive, StaleDocs staleDocs, UsageLog usage, Duration statusInterval) {
        this.tenants = tenants;
        this.agent = agent;
        this.knowledge = knowledge;
        this.budget = budget;
        this.conversations = conversations;
        this.interactions = interactions;
        this.messengers = messengers;
        this.corrections = corrections;
        this.pendingActions = pendingActions;
        this.identities = identities;
        this.proactive = proactive;
        this.staleDocs = staleDocs;
        this.usage = usage;
        this.statusIntervalMillis = statusInterval.toMillis();
    }

    /** Where a question was asked and where the reply goes. */
    private record Question(
            String channel,
            String replyThreadTs,        // null: reply unthreaded (a DM outside a thread)
            String conversationThreadTs, // '' for a DM outside a thread
            boolean dm,
            String user,
            String text) {}

    /**
     * Handles one incoming message. Cheap and non-blocking: safe to call on the Slack event
     * thread. The returned future completes when the event has been fully processed
     * (answered or ignored); it never completes exceptionally.
     */
    public CompletableFuture<Void> handle(long tenantId, IncomingEvent event) {
        if (event == null || event.fromBot() || event.subtype() != null || event.kind() == null
                || isBlank(event.channel()) || isBlank(event.user()) || isBlank(event.ts())) {
            return DONE;
        }
        String botUserId = botUserId(tenantId);
        if (event.user().equals(botUserId)) {
            return DONE;
        }
        if (event.kind() == IncomingEvent.Kind.CHANNEL_MESSAGE) {
            return handleChannelMessage(tenantId, event, botUserId);
        }
        boolean dm = event.kind() == IncomingEvent.Kind.DIRECT_MESSAGE;
        boolean threadReply = event.kind() == IncomingEvent.Kind.THREAD_REPLY;
        if (threadReply && isBlank(event.threadTs())) {
            return DONE;
        }
        if (event.eventId() != null && !seen.add(key(tenantId, "event:" + event.eventId()))) {
            return DONE;
        }
        // Channel: always the thread of the triggering message. DM: unthreaded unless written in a thread.
        String replyThreadTs = !isBlank(event.threadTs()) ? event.threadTs() : (dm ? null : event.ts());
        String conversationThreadTs = replyThreadTs == null ? "" : replyThreadTs;

        return enqueue(tenantId, event.channel(), conversationThreadTs, () -> {
            if (threadReply && !conversations.hasInteractions(tenantId, event.channel(), event.threadTs())) {
                return;
            }
            // An app_mention and the message event for the same message must give one answer.
            if (!seen.add(key(tenantId, "message:" + event.channel() + ":" + event.ts()))) {
                return;
            }
            String text = stripMention(event.text(), botUserId, event.kind() == IncomingEvent.Kind.MENTION);
            // A reply that corrects an answer goes to the admins; anything else is a question.
            if ((threadReply || dm) && !text.isEmpty() && corrections.handle(tenantId, event.channel(),
                    replyThreadTs, conversationThreadTs, dm, event.user(), text)) {
                return;
            }
            answer(tenantId, new Question(event.channel(), replyThreadTs, conversationThreadTs, dm, event.user(), text));
        });
    }

    /**
     * A top-level message in a channel nobody asked docs0 about. Ignored unless it reads like a question and
     * the channel is opted in; then docs0 may recognise it as a repeat and answer in its thread. Mentions are
     * left to the mention event, so a mentioned message is never answered twice.
     */
    private CompletableFuture<Void> handleChannelMessage(long tenantId, IncomingEvent event, String botUserId) {
        if (proactive == null || !tenants.isActive(tenantId) || !isBlank(event.threadTs()) || isDmChannel(event.channel())
                || isBlank(botUserId) || event.text() == null || event.text().contains("<@" + botUserId)) {
            return DONE;
        }
        String text = ProactiveResponder.plainText(event.text());
        if (!ProactiveResponder.looksLikeQuestion(text)) {
            return DONE;
        }
        if (event.eventId() != null && !seen.add(key(tenantId, "event:" + event.eventId()))) {
            return DONE;
        }
        return enqueue(tenantId, event.channel(), event.ts(), () -> {
            if (!seen.add(key(tenantId, "message:" + event.channel() + ":" + event.ts()))) {
                return;
            }
            if (!agent.available()) {
                return;
            }
            proactive.consider(tenantId, event.channel(), event.ts(), text).ifPresent(
                    offer -> answerProactively(tenantId, new Question(event.channel(), event.ts(), event.ts(), false,
                            event.user(), text), offer));
        });
    }

    /**
     * Handles a click on a follow-up button: shows who asked what in the thread, then
     * answers there on behalf of the clicker.
     *
     * @param threadTs the thread of the clicked message, or null for an unthreaded DM
     * @param actionKey unique per click, to drop duplicate deliveries; may be null
     */
    public CompletableFuture<Void> followUp(long tenantId, String channel, String threadTs, String user,
            String question, String actionKey) {
        if (isBlank(channel) || isBlank(user) || isBlank(question)) {
            return DONE;
        }
        if (actionKey != null && !seen.add(key(tenantId, "action:" + actionKey))) {
            return DONE;
        }
        String replyThreadTs = isBlank(threadTs) ? null : threadTs;
        String conversationThreadTs = replyThreadTs == null ? "" : replyThreadTs;
        boolean dm = isDmChannel(channel);
        String text = question.strip();

        return enqueue(tenantId, channel, conversationThreadTs, () -> {
            messenger(tenantId).postMessage(channel, replyThreadTs,
                    "*<@" + user + "> asked:* " + AnswerRenderer.escape(text), null);
            answer(tenantId, new Question(channel, replyThreadTs, conversationThreadTs, dm, user, text));
        });
    }

    /** Slack ids of direct message channels start with D. */
    static boolean isDmChannel(String channel) {
        return channel != null && channel.startsWith("D");
    }

    /** Removes docs0's own mention from the text. */
    static String stripMention(String text, String botUserId, boolean mention) {
        if (text == null) {
            return "";
        }
        String stripped = text;
        if (!isBlank(botUserId)) {
            stripped = stripped.replaceAll("<@" + Pattern.quote(botUserId) + "(?:\\|[^>]*)?>", " ");
        } else if (mention) {
            stripped = ANY_LEADING_MENTION.matcher(stripped).replaceFirst("");
        }
        return stripped.strip();
    }

    // ---- answering -------------------------------------------------------------------------

    private void answer(long tenantId, Question q) {
        String refusal;
        try {
            refusal = refusal(tenantId, q);
        } catch (Exception e) {
            log.warn("Question could not be checked: tenant={} channel={} error={}", tenantId, q.channel(),
                    e.getClass().getName());
            messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), APOLOGY, null);
            return;
        }
        if (refusal != null) {
            String ts = messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), refusal, null);
            if (refusal.equals(OVER_BUDGET)) {
                Conversation conversation = conversation(tenantId, q);
                interactions.insertOverBudget(tenantId, conversation.id(), q.user(), q.text(), OVER_BUDGET, ts);
                log.info("Question refused, monthly budget used up: tenant={} channel={}", tenantId, q.channel());
            }
            return;
        }

        String statusTs = messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), STATUS_SEARCHING, null);
        StatusThrottle status = new StatusThrottle(text -> updateStatus(tenantId, q.channel(), statusTs, text),
                statusIntervalMillis, () -> System.nanoTime() / 1_000_000, this::schedule);
        long startedAt = System.nanoTime();
        Conversation conversation = null;
        AgentResult result;
        try {
            conversation = conversation(tenantId, q);
            List<Exchange> history = conversations.history(tenantId, conversation.id());
            Audience audience = audience(tenantId, q);
            result = agent.answer(new AgentRequest(tenantId, audience, q.text(), history, conversation.id(), q.user()),
                    status::onStatus);
            if (result == null || result.kind() == null) {
                throw new IllegalStateException("agent returned no result");
            }
        } catch (Exception e) {
            log.warn("Answering failed: tenant={} channel={} error={}", tenantId, q.channel(), e.getClass().getName());
            result = failure(e, (System.nanoTime() - startedAt) / 1_000_000);
        } finally {
            // From here on nothing may overwrite the final message.
            status.close();
        }

        List<StaleDocs.Stale> stale = staleOf(tenantId, result);
        result = writeFinalMessage(tenantId, q.channel(), statusTs, result, StaleDocs.oldest(stale));

        if (conversation == null) {
            log.warn("Interaction not logged, no conversation: tenant={} channel={}", tenantId, q.channel());
            discardDrafts(tenantId, result);
            return;
        }
        try {
            long id = interactions.insert(tenantId, conversation.id(), q.user(), q.text(), result, statusTs);
            link(tenantId, result, id);
            if (result.kind() == Kind.ANSWER) {
                recordStale(tenantId, id, stale);
            }
            log.info("Answered: tenant={} interaction={} kind={} steps={} inputTokens={} outputTokens={} ms={}",
                    tenantId, id, result.kind(), result.steps(), result.inputTokens(), result.outputTokens(),
                    result.durationMs());
        } catch (Exception e) {
            log.warn("Interaction not logged: tenant={} conversation={} error={}", tenantId, conversation.id(),
                    e.getClass().getName());
        }
        postDrafts(tenantId, q, result);
    }

    // ---- unprompted answers and stale documents ----------------------------------------------------

    /**
     * Answers a repeat question the way an @mention would be answered (same audience, so the same access rules),
     * in a thread on the person's message. Unlike a mention it says nothing at all when anything goes wrong or the
     * answer is not good enough: no status line, no apology. The cost of an answer that is not shown goes to the
     * usage log, so the monthly cap still sees it.
     */
    private void answerProactively(long tenantId, Question q, ProactiveResponder.Offer offer) {
        AgentResult result = null;
        boolean shown = false;
        boolean logged = false;
        try {
            if (!agent.available() || budget.exhausted(tenantId) || knowledge.count(tenantId) == 0) {
                return;
            }
            Audience audience = audience(tenantId, q);
            result = agent.answer(new AgentRequest(tenantId, audience, q.text(), List.of()), Agent.ProgressListener.NONE);
            if (result == null || result.kind() != Kind.ANSWER || result.confidence() == Confidence.LOW
                    || result.citations() == null || result.citations().isEmpty()
                    || (result.drafts() != null && !result.drafts().isEmpty())) {
                log.info("Repeat question not answered: tenant={} channel={} kind={}", tenantId, q.channel(),
                        result == null ? null : result.kind());
                return;
            }
            List<StaleDocs.Stale> stale = staleOf(tenantId, result);
            AnswerRenderer.Rendered rendered = AnswerRenderer.render(result, ProactiveResponder.LEAD,
                    StaleDocs.oldest(stale));
            String ts;
            try {
                ts = messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), rendered.text(), rendered.blocks());
            } catch (Exception e) {
                log.warn("Answer layout rejected, sending plain text: channel={} error={}", q.channel(), reason(e));
                ts = messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), rendered.text(), null);
            }
            shown = true;
            Conversation conversation = conversation(tenantId, q);
            long id = interactions.insert(tenantId, conversation.id(), q.user(), q.text(), result, ts);
            logged = true;
            proactive.answered(tenantId, offer, id);
            recordStale(tenantId, id, stale);
            log.info("Answered a repeat question: tenant={} interaction={} offer={} inputTokens={} outputTokens={}",
                    tenantId, id, offer.offerId(), result.inputTokens(), result.outputTokens());
        } catch (Exception e) {
            log.warn("Repeat question not answered: tenant={} channel={} error={}", tenantId, q.channel(), reason(e));
        } finally {
            if (result != null && !shown) {
                discardDrafts(tenantId, result);
            }
            // An answer that is not in the interactions table is not in the cost report either: never lose cost.
            if (result != null && !logged) {
                recordUsage(tenantId, result);
            }
        }
    }

    /** The cost of an unprompted answer that was not shown, so the monthly cap counts it. */
    private void recordUsage(long tenantId, AgentResult result) {
        if (usage == null) {
            return;
        }
        try {
            usage.record(tenantId, UsageLog.PROACTIVE_ANSWER, result.inputTokens(), result.outputTokens(),
                    result.costUsd() == null ? BigDecimal.ZERO : result.costUsd());
        } catch (RuntimeException e) {
            log.warn("Cost not logged: tenant={} error={}", tenantId, e.getClass().getName());
        }
    }

    /** The stale documents among an answer's citations. Never fails: no note is better than no answer. */
    private List<StaleDocs.Stale> staleOf(long tenantId, AgentResult result) {
        if (staleDocs == null || result == null || result.kind() != Kind.ANSWER) {
            return List.of();
        }
        try {
            return staleDocs.staleAmong(tenantId, result.citations());
        } catch (RuntimeException e) {
            log.warn("Stale documents not checked: tenant={} error={}", tenantId, e.getClass().getName());
            return List.of();
        }
    }

    private void recordStale(long tenantId, long interactionId, List<StaleDocs.Stale> stale) {
        if (staleDocs == null || stale.isEmpty()) {
            return;
        }
        try {
            staleDocs.record(tenantId, interactionId, stale);
        } catch (RuntimeException e) {
            log.warn("Stale documents not recorded: tenant={} interaction={} error={}", tenantId, interactionId,
                    e.getClass().getName());
        }
    }

    /** Ties the drafts of an answer to the interaction that produced them. */
    private void link(long tenantId, AgentResult result, long interactionId) {
        if (result.drafts() == null || result.drafts().isEmpty()) {
            return;
        }
        try {
            pendingActions.attachInteraction(tenantId, result.drafts().stream().map(PendingAction::id).toList(),
                    interactionId);
        } catch (RuntimeException e) {
            log.warn("Drafts not linked to the interaction: tenant={} error={}", tenantId, e.getClass().getName());
        }
    }

    /**
     * Posts each draft of the answer in the thread, with its Create and Cancel buttons. A draft
     * that could not be shown is dropped, so nothing can be confirmed that nobody saw.
     */
    private void postDrafts(long tenantId, Question q, AgentResult result) {
        if (result.drafts() == null || result.drafts().isEmpty()) {
            return;
        }
        if (result.kind() == Kind.ERROR) {
            discardDrafts(tenantId, result);
            return;
        }
        for (PendingAction draft : result.drafts()) {
            try {
                AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraft(draft);
                messenger(tenantId).postMessage(q.channel(), q.replyThreadTs(), rendered.text(), rendered.blocks());
            } catch (RuntimeException e) {
                log.warn("Draft could not be shown: tenant={} draft={} error={}", tenantId, draft.id(), reason(e));
                pendingActions.discard(tenantId, draft.id());
            }
        }
    }

    private void discardDrafts(long tenantId, AgentResult result) {
        if (result.drafts() == null) {
            return;
        }
        for (PendingAction draft : result.drafts()) {
            try {
                pendingActions.discard(tenantId, draft.id());
            } catch (RuntimeException e) {
                log.warn("Draft not discarded: tenant={} draft={}", tenantId, draft.id());
            }
        }
    }

    /** The reply to send instead of answering, or null when the question can go to the agent. */
    private String refusal(long tenantId, Question q) {
        if (!tenants.isActive(tenantId)) {
            return SUSPENDED;
        }
        if (q.text().isEmpty()) {
            return HINT;
        }
        if (!agent.available()) {
            return NOT_CONNECTED;
        }
        if (budget.exhausted(tenantId)) {
            return OVER_BUDGET;
        }
        if (knowledge.count(tenantId) == 0) {
            return NO_SOURCES;
        }
        return null;
    }

    /**
     * Replaces the status message with the answer, or with an apology when answering failed.
     * Returns the result to log: unchanged, or an error if the answer could not be shown.
     */
    private AgentResult writeFinalMessage(long tenantId, String channel, String statusTs, AgentResult result, Instant staleSince) {
        if (result.kind() != Kind.ERROR) {
            try {
                AnswerRenderer.Rendered rendered = AnswerRenderer.render(result, null, staleSince);
                try {
                    messenger(tenantId).updateMessage(channel, statusTs, rendered.text(), rendered.blocks());
                } catch (Exception e) {
                    // Slack rejected the layout: show the answer as plain text rather than lose it.
                    log.warn("Answer layout rejected, sending plain text: channel={} error={}", channel, reason(e));
                    messenger(tenantId).updateMessage(channel, statusTs, rendered.text(), null);
                }
                return result;
            } catch (Exception e) {
                log.warn("Answer could not be shown: channel={} error={}", channel, reason(e));
                result = new AgentResult(Kind.ERROR, result.answer(), result.confidence(), result.citations(),
                        result.followUps(), result.suggestedPeople(), result.steps(), result.inputTokens(),
                        result.outputTokens(), result.costUsd(), result.durationMs(),
                        "could not show the answer in Slack: " + reason(e), result.correctionIds(), result.drafts(), result.usedRestricted());
            }
        }
        try {
            messenger(tenantId).updateMessage(channel, statusTs, APOLOGY, null);
        } catch (Exception e) {
            log.warn("Apology could not be shown: channel={} error={}", channel, reason(e));
        }
        return result;
    }

    private void updateStatus(long tenantId, String channel, String statusTs, String text) {
        try {
            // Status lines can carry document titles and search words: neutralise Slack's control characters.
            String line = AnswerRenderer.escape(text.strip());
            messenger(tenantId).updateMessage(channel, statusTs,
                    line.length() <= MAX_STATUS_CHARS ? line : line.substring(0, MAX_STATUS_CHARS - 1) + "…", null);
        } catch (Exception e) {
            log.debug("Status update failed: channel={} error={}", channel, reason(e));
        }
    }

    private Conversation conversation(long tenantId, Question q) {
        Boolean channelPrivate = q.dm() ? Boolean.TRUE : knownPrivacy(tenantId, q.channel());
        return conversations.findOrCreate(tenantId, q.channel(), q.conversationThreadTs(), q.dm(), channelPrivate);
    }

    /** TRUE for a private channel, FALSE for a public one, null when Slack cannot say. Uses the privacy cache. */
    private Boolean knownPrivacy(long tenantId, String channel) {
        SlackMessenger.ChannelPrivacy value = lookupPrivacy(tenantId, channel);
        return value == null ? null : value == SlackMessenger.ChannelPrivacy.PRIVATE;
    }

    /**
     * Everyone who will see the answer: just the asker in a DM, the members of a private channel, and nobody
     * (shared knowledge only) in a public channel or one whose privacy cannot be determined, because anyone in the
     * workspace can read a public channel. Bots, deactivated users and docs0 itself are not part of it. Each person
     * carries their email when known, so documents shared by email can be matched. Anyone Slack cannot describe stays
     * in as a plain Slack id, which can only make fewer restricted documents eligible, never more. Every answer,
     * prompted or not, gets its audience here.
     */
    private Audience audience(long tenantId, Question q) {
        if (q.dm()) {
            return new Audience(Set.of(asker(tenantId, q.user())));
        }
        if (!isPrivateChannel(tenantId, q.channel())) {
            return Audience.sharedOnly();
        }
        try {
            Set<String> ids = messenger(tenantId).channelMembers(q.channel());
            if (ids != null && !ids.isEmpty()) {
                Audience audience = people(tenantId, ids);
                if (audience.members().size() < 2) {
                    // A channel with one person (plus bots) is not a private conversation: more people can
                    // join later and read the answer. Only a DM counts as one person's own audience.
                    return Audience.sharedOnly();
                }
                return audience;
            }
            log.warn("Channel has no listed members, answering from shared knowledge only: channel={}", q.channel());
        } catch (Exception e) {
            log.warn("Channel member lookup failed, answering from shared knowledge only: channel={} error={}",
                    q.channel(), reason(e));
        }
        // Unknown audience: everyone in the channel will see the answer, so use only shared knowledge.
        return Audience.sharedOnly();
    }

    /** True only when Slack says the channel is private. A public channel, or any failure, is not private. */
    private boolean isPrivateChannel(long tenantId, String channel) {
        return lookupPrivacy(tenantId, channel) == SlackMessenger.ChannelPrivacy.PRIVATE;
    }

    /** The channel's privacy from the cache or Slack; null when Slack cannot say (failures are not cached). */
    private SlackMessenger.ChannelPrivacy lookupPrivacy(long tenantId, String channel) {
        String key = key(tenantId, channel);
        long now = System.nanoTime();
        CachedPrivacy cached = privacy.get(key);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.value();
        }
        try {
            SlackMessenger.ChannelPrivacy value = messenger(tenantId).channelPrivacy(channel);
            if (value == null) {
                return null;
            }
            if (privacy.size() >= PRIVACY_CAPACITY) {
                privacy.clear();
            }
            privacy.put(key, new CachedPrivacy(value, now + PRIVACY_TTL_NANOS));
            return value;
        } catch (Exception e) {
            log.warn("Channel privacy lookup failed, answering from shared knowledge only: channel={} error={}",
                    channel, reason(e));
            return null;
        }
    }

    private Audience people(long tenantId, Set<String> ids) {
        String own = botUserId(tenantId);
        Set<String> humans = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank() && !id.equals(own)) {
                humans.add(id);
            }
        }
        Map<String, IdentityService.Person> known = identities.resolve(tenantId, humans, IdentityService.DEFAULT_BUDGET);
        Set<Audience.Member> members = new java.util.LinkedHashSet<>();
        for (String id : humans) {
            IdentityService.Person person = known.get(id);
            if (person == null) {
                members.add(new Audience.Member(id));
            } else if (!person.bot() && !person.deleted()) {
                members.add(new Audience.Member(id, person.email()));
            }
        }
        return new Audience(members);
    }

    private Audience.Member asker(long tenantId, String user) {
        try {
            return identities.person(tenantId, user).map(p -> new Audience.Member(user, p.email()))
                    .orElse(new Audience.Member(user));
        } catch (RuntimeException e) {
            return new Audience.Member(user);
        }
    }

    private static AgentResult failure(Exception e, long durationMs) {
        String error = e.toString();
        return new AgentResult(Kind.ERROR, null, Confidence.LOW, List.of(), List.of(), List.of(), 0, 0, 0,
                BigDecimal.ZERO, durationMs,
                error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS));
    }

    // ---- plumbing --------------------------------------------------------------------------

    /** Runs the task after every earlier task of the same conversation has finished. */
    private CompletableFuture<Void> enqueue(long tenantId, String channel, String conversationThreadTs, Runnable task) {
        // Slack ids are only unique within a workspace, so the tenant is part of every key.
        String key = key(tenantId, channel + ":" + conversationThreadTs);
        CompletableFuture<Void> queued = queues.compute(key, (k, tail) -> {
            CompletableFuture<Void> previous = tail == null ? DONE : tail;
            return previous.handleAsync((ok, failed) -> {
                runSafely(channel, task);
                return null;
            }, workers);
        });
        queued.whenComplete((ok, failed) -> queues.remove(key, queued));
        return queued;
    }

    private void runSafely(String channel, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.warn("Slack event could not be handled: channel={} error={}", channel, reason(t));
        }
    }

    private Runnable schedule(Runnable task, long delayMillis) {
        // The timer thread only starts the work; the Slack call runs on a virtual thread.
        ScheduledFuture<?> scheduled = statusTimer.schedule(() -> { workers.execute(task); }, delayMillis,
                TimeUnit.MILLISECONDS);
        return () -> scheduled.cancel(false);
    }

    private SlackMessenger messenger(long tenantId) {
        return messengers.forTenant(tenantId);
    }

    /** A de-duplication or queue key that two tenants can never share, whatever Slack ids they have in common. */
    private static String key(long tenantId, String id) {
        return tenantId + "|" + id;
    }

    private String botUserId(long tenantId) {
        try {
            return messenger(tenantId).botUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** A short, safe description of a failure for the log: Slack's error code or the exception type. */
    private static String reason(Throwable t) {
        return t instanceof SlackMessenger.SlackCallException slack ? slack.code() : t.getClass().getName();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

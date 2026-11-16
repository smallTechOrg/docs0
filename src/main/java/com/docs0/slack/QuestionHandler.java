package com.docs0.slack;

import com.docs0.agent.Agent;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.BudgetService;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
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
    static final String APOLOGY = "Sorry, something went wrong while I was working on that. Please try asking again.";

    static final Duration STATUS_INTERVAL = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(QuestionHandler.class);
    private static final Pattern ANY_LEADING_MENTION = Pattern.compile("^\\s*<@[A-Z0-9]+(?:\\|[^>]*)?>");
    private static final int MAX_STATUS_CHARS = 300;
    private static final int MAX_ERROR_CHARS = 1000;
    private static final int DEDUPE_CAPACITY = 5000;
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final TenantService tenants;
    private final Agent agent;
    private final KnowledgeSearch knowledge;
    private final BudgetService budget;
    private final ConversationRepository conversations;
    private final InteractionRepository interactions;
    private final SlackMessenger messenger;
    private final CorrectionFlow corrections;
    private final long statusIntervalMillis;

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
            ConversationRepository conversations, InteractionRepository interactions, SlackMessenger messenger,
            CorrectionFlow corrections) {
        this(tenants, agent, knowledge, budget, conversations, interactions, messenger, corrections, STATUS_INTERVAL);
    }

    /** For tests: {@code statusInterval} is the minimum time between two status line updates. */
    QuestionHandler(TenantService tenants, Agent agent, KnowledgeSearch knowledge, BudgetService budget,
            ConversationRepository conversations, InteractionRepository interactions, SlackMessenger messenger,
            CorrectionFlow corrections, Duration statusInterval) {
        this.tenants = tenants;
        this.agent = agent;
        this.knowledge = knowledge;
        this.budget = budget;
        this.conversations = conversations;
        this.interactions = interactions;
        this.messenger = messenger;
        this.corrections = corrections;
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
    public CompletableFuture<Void> handle(IncomingEvent event) {
        if (event == null || event.fromBot() || event.subtype() != null || event.kind() == null
                || isBlank(event.channel()) || isBlank(event.user()) || isBlank(event.ts())) {
            return DONE;
        }
        String botUserId = botUserId();
        if (event.user().equals(botUserId)) {
            return DONE;
        }
        boolean dm = event.kind() == IncomingEvent.Kind.DIRECT_MESSAGE;
        boolean threadReply = event.kind() == IncomingEvent.Kind.THREAD_REPLY;
        if (threadReply && isBlank(event.threadTs())) {
            return DONE;
        }
        if (event.eventId() != null && !seen.add("event:" + event.eventId())) {
            return DONE;
        }
        // Channel: always the thread of the triggering message. DM: unthreaded unless written in a thread.
        String replyThreadTs = !isBlank(event.threadTs()) ? event.threadTs() : (dm ? null : event.ts());
        String conversationThreadTs = replyThreadTs == null ? "" : replyThreadTs;

        return enqueue(event.channel(), conversationThreadTs, () -> {
            long tenantId = tenants.current().id();
            if (threadReply && !conversations.hasInteractions(tenantId, event.channel(), event.threadTs())) {
                return;
            }
            // An app_mention and the message event for the same message must give one answer.
            if (!seen.add("message:" + event.channel() + ":" + event.ts())) {
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
     * Handles a click on a follow-up button: shows who asked what in the thread, then
     * answers there on behalf of the clicker.
     *
     * @param threadTs the thread of the clicked message, or null for an unthreaded DM
     * @param actionKey unique per click, to drop duplicate deliveries; may be null
     */
    public CompletableFuture<Void> followUp(String channel, String threadTs, String user, String question,
            String actionKey) {
        if (isBlank(channel) || isBlank(user) || isBlank(question)) {
            return DONE;
        }
        if (actionKey != null && !seen.add("action:" + actionKey)) {
            return DONE;
        }
        String replyThreadTs = isBlank(threadTs) ? null : threadTs;
        String conversationThreadTs = replyThreadTs == null ? "" : replyThreadTs;
        boolean dm = isDmChannel(channel);
        String text = question.strip();

        return enqueue(channel, conversationThreadTs, () -> {
            long tenantId = tenants.current().id();
            messenger.postMessage(channel, replyThreadTs,
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
            messenger.postMessage(q.channel(), q.replyThreadTs(), APOLOGY, null);
            return;
        }
        if (refusal != null) {
            String ts = messenger.postMessage(q.channel(), q.replyThreadTs(), refusal, null);
            if (refusal.equals(OVER_BUDGET)) {
                Conversation conversation = conversation(tenantId, q);
                interactions.insertOverBudget(tenantId, conversation.id(), q.user(), q.text(), OVER_BUDGET, ts);
                log.info("Question refused, monthly budget used up: tenant={} channel={}", tenantId, q.channel());
            }
            return;
        }

        String statusTs = messenger.postMessage(q.channel(), q.replyThreadTs(), STATUS_SEARCHING, null);
        StatusThrottle status = new StatusThrottle(text -> updateStatus(q.channel(), statusTs, text),
                statusIntervalMillis, () -> System.nanoTime() / 1_000_000, this::schedule);
        long startedAt = System.nanoTime();
        Conversation conversation = null;
        AgentResult result;
        try {
            conversation = conversation(tenantId, q);
            List<Exchange> history = conversations.history(tenantId, conversation.id());
            Audience audience = audience(q);
            result = agent.answer(new AgentRequest(tenantId, audience, q.text(), history), status::onStatus);
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

        result = writeFinalMessage(q.channel(), statusTs, result);

        if (conversation == null) {
            log.warn("Interaction not logged, no conversation: tenant={} channel={}", tenantId, q.channel());
            return;
        }
        try {
            long id = interactions.insert(tenantId, conversation.id(), q.user(), q.text(), result, statusTs);
            log.info("Answered: tenant={} interaction={} kind={} steps={} inputTokens={} outputTokens={} ms={}",
                    tenantId, id, result.kind(), result.steps(), result.inputTokens(), result.outputTokens(),
                    result.durationMs());
        } catch (Exception e) {
            log.warn("Interaction not logged: tenant={} conversation={} error={}", tenantId, conversation.id(),
                    e.getClass().getName());
        }
    }

    /** The reply to send instead of answering, or null when the question can go to the agent. */
    private String refusal(long tenantId, Question q) {
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
    private AgentResult writeFinalMessage(String channel, String statusTs, AgentResult result) {
        if (result.kind() != Kind.ERROR) {
            try {
                AnswerRenderer.Rendered rendered = AnswerRenderer.render(result);
                try {
                    messenger.updateMessage(channel, statusTs, rendered.text(), rendered.blocks());
                } catch (Exception e) {
                    // Slack rejected the layout: show the answer as plain text rather than lose it.
                    log.warn("Answer layout rejected, sending plain text: channel={} error={}", channel, reason(e));
                    messenger.updateMessage(channel, statusTs, rendered.text(), null);
                }
                return result;
            } catch (Exception e) {
                log.warn("Answer could not be shown: channel={} error={}", channel, reason(e));
                result = new AgentResult(Kind.ERROR, result.answer(), result.confidence(), result.citations(),
                        result.followUps(), result.suggestedPeople(), result.steps(), result.inputTokens(),
                        result.outputTokens(), result.costUsd(), result.durationMs(),
                        "could not show the answer in Slack: " + reason(e), result.correctionIds());
            }
        }
        try {
            messenger.updateMessage(channel, statusTs, APOLOGY, null);
        } catch (Exception e) {
            log.warn("Apology could not be shown: channel={} error={}", channel, reason(e));
        }
        return result;
    }

    private void updateStatus(String channel, String statusTs, String text) {
        try {
            String line = text.strip();
            messenger.updateMessage(channel, statusTs,
                    line.length() <= MAX_STATUS_CHARS ? line : line.substring(0, MAX_STATUS_CHARS - 1) + "…", null);
        } catch (Exception e) {
            log.debug("Status update failed: channel={} error={}", channel, reason(e));
        }
    }

    private Conversation conversation(long tenantId, Question q) {
        return conversations.findOrCreate(tenantId, q.channel(), q.conversationThreadTs(), q.dm());
    }

    /** Everyone who will see the answer: the channel's members, or just the asker in a DM. */
    private Audience audience(Question q) {
        if (q.dm()) {
            return Audience.of(q.user());
        }
        try {
            Set<String> members = messenger.channelMembers(q.channel());
            if (members != null && !members.isEmpty()) {
                return new Audience(members);
            }
            log.warn("Channel has no listed members, using the asker as audience: channel={}", q.channel());
        } catch (Exception e) {
            log.warn("Channel member lookup failed, using the asker as audience: channel={} error={}", q.channel(),
                    reason(e));
        }
        return Audience.of(q.user());
    }

    private static AgentResult failure(Exception e, long durationMs) {
        String error = e.toString();
        return new AgentResult(Kind.ERROR, null, Confidence.LOW, List.of(), List.of(), List.of(), 0, 0, 0,
                BigDecimal.ZERO, durationMs,
                error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS));
    }

    // ---- plumbing --------------------------------------------------------------------------

    /** Runs the task after every earlier task of the same conversation has finished. */
    private CompletableFuture<Void> enqueue(String channel, String conversationThreadTs, Runnable task) {
        String key = channel + ":" + conversationThreadTs;
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

    private String botUserId() {
        try {
            return messenger.botUserId();
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

package com.docs0.web.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.slack.SlackHttpEvents;
import com.docs0.slack.SlackSignature;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack's Events API and interactivity over HTTP. Every request is verified against the signing secret on its raw
 * bytes before anything is parsed; a request that fails gets a bare 401. A verified request is answered at once with
 * 200 and handled on a virtual thread, because Slack gives an app three seconds.
 *
 * <p>With {@code DOCS0_SLACK_TRANSPORT=http} (hosted on Cloud Run, request-based CPU) work done after the response is
 * frozen, so the request is instead held open until the handler has finished (at most
 * {@code DOCS0_SLACK_HOLD_OPEN_SECONDS}) and only then answered 200 with an empty body. Slack gives up after three
 * seconds and sends the same event again with {@code X-Slack-Retry-Num}; docs0 answers that retry (and any event it
 * has already accepted, finished or still in flight) 200 at once without running it a second time. The held request
 * keeps running after Slack has hung up, so the answer is still posted; the "failed to deliver" notices Slack shows
 * for the first attempt are harmless.
 */
@RestController
public class SlackEventsController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventsController.class);
    private static final String RETRY_HEADER = "X-Slack-Retry-Num";
    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final int SEEN_EVENTS = 10_000;

    private final Docs0Properties props;
    private final SlackHttpEvents events;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Event ids already accepted, so a retry of one is dropped. Bounded; the handlers de-duplicate as well. */
    private final Map<String, Boolean> seen = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_EVENTS;
                }
            });

    public SlackEventsController(Docs0Properties props, SlackHttpEvents events, Clock clock) {
        this.props = props;
        this.events = events;
        this.clock = clock;
    }

    @PostMapping("/slack/events")
    public ResponseEntity<String> events(HttpServletRequest request) throws IOException {
        byte[] body = verified(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonObject envelope = parse(body);
        String type = text(envelope, "type");
        if ("url_verification".equals(type)) {
            String challenge = text(envelope, "challenge");
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge == null ? "" : challenge);
        }
        if ("event_callback".equals(type)) {
            String eventId = SlackHttpEvents.eventId(envelope);
            boolean first = eventId == null || seen.put(eventId, Boolean.TRUE) == null;
            if (holdOpen()) {
                // An event already accepted (finished or in flight) has nothing to do and nothing to wait for. A
                // retry of an event this instance never saw is run: the first attempt must have been lost.
                if (first) {
                    await(runNow(() -> events.eventCallback(envelope)));
                }
            } else if (first || request.getHeader(RETRY_HEADER) == null) {
                executor.execute(() -> run(() -> events.eventCallback(envelope)));
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/slack/interactions")
    public ResponseEntity<String> interactions(HttpServletRequest request) throws IOException {
        byte[] body = verified(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (holdOpen()) {
            await(runNow(() -> events.interaction(body)));
        } else {
            executor.execute(() -> run(() -> events.interaction(body)));
        }
        return ResponseEntity.ok().build();
    }

    /** The raw body when the request is correctly signed and recent; null otherwise. Reads the body once. */
    private byte[] verified(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return null;
        }
        boolean ok = props.slack() != null && SlackSignature.valid(props.slack().signingSecret(),
                request.getHeader(SlackSignature.TIMESTAMP_HEADER), request.getHeader(SlackSignature.SIGNATURE_HEADER),
                body, clock.instant());
        if (!ok) {
            log.info("Slack request rejected: signature or timestamp not valid");
            return null;
        }
        return body;
    }

    private boolean holdOpen() {
        return props.slack() != null && props.slack().httpTransport();
    }

    /** Starts the handler on this thread; whatever it throws becomes a completed future (never an error response). */
    private static CompletableFuture<Void> runNow(Supplier<CompletableFuture<Void>> work) {
        try {
            CompletableFuture<Void> future = work.get();
            return future == null ? CompletableFuture.completedFuture(null) : future;
        } catch (RuntimeException e) {
            log.warn("Slack request could not be handled: {}", e.getClass().getName());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Holds the request until the handler is done, or the hold-open time is up (the work then carries on). */
    private void await(CompletableFuture<Void> work) {
        try {
            work.get(props.slack().holdOpen().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("Slack request still being worked on after {} s; answering 200", props.slack().holdOpen().toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Slack request could not be handled: {}", e.getClass().getName());
        }
    }

    private static JsonObject parse(byte[] body) {
        try {
            var parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject object, String field) {
        var value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static void run(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Slack request could not be handled: {}", e.getClass().getName());
        }
    }
}

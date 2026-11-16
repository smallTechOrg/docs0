package com.docs0.slack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.ReactionAddedEvent;
import com.slack.api.model.event.ReactionRemovedEvent;
import com.slack.api.util.json.GsonFactory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a Slack request that has already passed signature verification into calls on {@link SlackGateway}, the same
 * methods Socket Mode uses. Parsing the raw payload is the only thing this class adds; which tenant a workspace
 * belongs to, de-duplication and answering are all decided downstream.
 */
@Component
public class SlackHttpEvents {

    private static final Logger log = LoggerFactory.getLogger(SlackHttpEvents.class);
    private static final Gson GSON = GsonFactory.createSnakeCase();

    private final SlackGateway gateway;
    private final SlackWorkspaces workspaces;

    public SlackHttpEvents(SlackGateway gateway, SlackWorkspaces workspaces) {
        this.gateway = gateway;
        this.workspaces = workspaces;
    }

    /** The event id of an {@code event_callback} body, or null. Used to recognise Slack's retries. */
    public static String eventId(JsonObject envelope) {
        return SlackHttp.text(envelope, "event_id");
    }

    /** Handles the {@code event_callback} envelope of /slack/events. Never throws for a payload it does not know. */
    public void eventCallback(JsonObject envelope) {
        String teamId = SlackHttp.text(envelope, "team_id");
        String eventId = eventId(envelope);
        JsonObject event = SlackHttp.object(envelope, "event");
        String type = SlackHttp.text(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "app_mention" -> gateway.appMention(teamId, GSON.fromJson(event, AppMentionEvent.class), eventId);
            // Bolt hands only subtype-less messages to its message handler; the rest are edits, joins, bot posts...
            case "message" -> {
                if (SlackHttp.text(event, "subtype") == null) {
                    gateway.message(teamId, GSON.fromJson(event, MessageEvent.class), eventId);
                }
            }
            case "reaction_added" -> gateway.reactionAdded(teamId, GSON.fromJson(event, ReactionAddedEvent.class));
            case "reaction_removed" ->
                    gateway.reactionRemoved(teamId, GSON.fromJson(event, ReactionRemovedEvent.class));
            case "app_uninstalled" -> workspaces.markRevoked(teamId);
            case "tokens_revoked" -> {
                // Only the bot's token matters: a person revoking their own token does not remove docs0.
                var bots = SlackHttp.object(event, "tokens").get("bot");
                if (bots != null && bots.isJsonArray() && !bots.getAsJsonArray().isEmpty()) {
                    workspaces.markRevoked(teamId);
                }
            }
            default -> log.debug("Slack event type not used: {}", type.replaceAll("[^A-Za-z0-9_.]", ""));
        }
    }

    /** Handles the body of /slack/interactions: a form with one field, {@code payload}, holding JSON. */
    public void interaction(byte[] rawBody) {
        String json = payloadField(new String(rawBody, StandardCharsets.UTF_8));
        if (json == null) {
            return;
        }
        JsonObject payload = SlackHttp.json(json);
        if (!"block_actions".equals(SlackHttp.text(payload, "type"))) {
            return;
        }
        gateway.blockAction(GSON.fromJson(payload, BlockActionPayload.class));
    }

    /** The decoded {@code payload} field of a form body, read from the raw text. */
    static String payloadField(String form) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("payload")) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}

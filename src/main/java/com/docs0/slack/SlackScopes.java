package com.docs0.slack;

import java.util.List;

/**
 * What docs0 asks Slack for. The one list behind Add to Slack and Sign in with Slack; a test checks it against
 * slack/manifest.yml and slack/manifest-distributed.yml so the three cannot drift apart.
 */
public final class SlackScopes {

    /** Bot token scopes. */
    public static final List<String> BOT = List.of("app_mentions:read", "chat:write", "channels:history",
            "groups:history", "im:history", "mpim:history", "channels:read", "groups:read", "im:read", "im:write",
            "mpim:read", "users:read", "users:read.email", "channels:join", "reactions:read");

    /** Events the app subscribes to. */
    public static final List<String> BOT_EVENTS = List.of("app_mention", "message.channels", "message.groups",
            "message.im", "message.mpim", "reaction_added", "reaction_removed");

    /** Sign in with Slack (OpenID Connect) scopes. */
    public static final List<String> SIGN_IN = List.of("openid", "email", "profile");

    private SlackScopes() {}
}

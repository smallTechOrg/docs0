package com.docs0.tenant.local;

import com.docs0.config.Docs0Properties;
import com.docs0.slack.BoltSlackMessenger;
import com.docs0.slack.SlackWorkspaces;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * The Socket Mode pilot: one Slack app whose tokens come from DOCS0_SLACK_BOT_TOKEN and DOCS0_SLACK_APP_TOKEN, serving
 * the configured tenant. At startup its workspace is registered for that tenant like any other installation, so from
 * then on the pilot is routed and served through the same workspace registry as every other tenant.
 */
@Component
public class LocalSlackPilot {

    private final Docs0Properties props;
    private final LocalTenantResolver local;
    private final SlackWorkspaces workspaces;

    public LocalSlackPilot(Docs0Properties props, LocalTenantResolver local, SlackWorkspaces workspaces) {
        this.props = props;
        this.local = local;
        this.workspaces = workspaces;
    }

    /** The tenant the configured Slack tokens belong to. */
    public long tenantId() {
        return local.tenant().id();
    }

    /**
     * Asks Slack (auth.test) who the configured bot token belongs to and registers that workspace for the configured
     * tenant. Throws the Slack call failure when Slack cannot be reached or rejects the token; the caller retries.
     */
    public BoltSlackMessenger.Identity register() {
        String token = props.slack().botToken();
        BoltSlackMessenger messenger = new BoltSlackMessenger(token);
        BoltSlackMessenger.Identity identity = messenger.identify();
        String scopes = String.join(",", new TreeSet<>(messenger.botScopes()));
        workspaces.register(tenantId(), identity.teamId(), identity.team(), token, identity.botUserId(),
                "socket-mode", scopes.isEmpty() ? null : scopes);
        return identity;
    }
}

package com.docs0.slack;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@link SlackMessengers} backed by the workspace registry: a {@link BoltSlackMessenger} per tenant, built from the
 * tenant's own bot token and kept until the tenant is registered again (the installation's update time changes).
 */
@Component
public class WorkspaceSlackMessengers implements SlackMessengers {

    private record Cached(Instant installedAt, SlackMessenger messenger) {}

    private final SlackWorkspaces workspaces;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public WorkspaceSlackMessengers(SlackWorkspaces workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public boolean connected(long tenantId) {
        return workspaces.forTenant(tenantId).isPresent();
    }

    @Override
    public SlackMessenger forTenant(long tenantId) {
        SlackWorkspaces.Installation installation = workspaces.forTenant(tenantId).orElseThrow(
                () -> new SlackMessenger.SlackCallException("workspace", "no_workspace", null));
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.installedAt().equals(installation.updatedAt())) {
            return cached.messenger();
        }
        String token = workspaces.botToken(tenantId).orElseThrow(
                () -> new SlackMessenger.SlackCallException("workspace", "no_workspace", null));
        Cached fresh = new Cached(installation.updatedAt(), new BoltSlackMessenger(token));
        cache.put(tenantId, fresh);
        return fresh.messenger();
    }
}

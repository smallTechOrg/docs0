package com.docs0.slack;

/**
 * The Slack client of a tenant's own workspace. Every class that talks to Slack asks for the messenger of the
 * tenant it is working for; there is no shared client with a global token.
 */
@FunctionalInterface
public interface SlackMessengers {

    /**
     * The messenger for this tenant's workspace.
     *
     * @throws SlackMessenger.SlackCallException with code {@code no_workspace} when the tenant has no workspace
     */
    SlackMessenger forTenant(long tenantId);

    /** True when the tenant has a workspace to talk to. Cheap: does not build a client. */
    default boolean connected(long tenantId) {
        try {
            forTenant(tenantId);
            return true;
        } catch (SlackMessenger.SlackCallException e) {
            return !"no_workspace".equals(e.code());
        }
    }

    /** One messenger for every tenant. For tests and single-workspace fakes. */
    static SlackMessengers fixed(SlackMessenger messenger) {
        return tenantId -> messenger;
    }
}

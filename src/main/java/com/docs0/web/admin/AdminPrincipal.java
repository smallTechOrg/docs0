package com.docs0.web.admin;

/**
 * Who is using the admin pages and which tenant they administer. Established by {@link AdminGuard} through an
 * {@link AdminAuthenticator}; controllers receive it as a method parameter and never look a tenant up themselves.
 */
public record AdminPrincipal(long tenantId, String displayName, String email, String slackUserId) {

    /** A principal that cannot be re-checked against Slack (the operator's password or this machine). */
    public AdminPrincipal(long tenantId, String displayName) {
        this(tenantId, displayName, null, null);
    }
}

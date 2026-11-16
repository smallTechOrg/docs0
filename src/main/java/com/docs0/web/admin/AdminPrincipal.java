package com.docs0.web.admin;

/**
 * Who is using the admin pages and which tenant they administer. Established by {@link AdminGuard} through an
 * {@link AdminAuthenticator}; controllers receive it as a method parameter and never look a tenant up themselves.
 */
public record AdminPrincipal(long tenantId, String displayName) {}

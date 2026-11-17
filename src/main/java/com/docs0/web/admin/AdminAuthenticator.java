package com.docs0.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * How an admin proves who they are, and for which tenant. The one place that decides this: {@link AdminGuard}
 * asks it for every /admin request. Today there is a single implementation (the configured password, or the local
 * machine, for the configured tenant); other sign-ins plug in here.
 */
public interface AdminAuthenticator {

    /** The principal for this request, or empty when the request is not allowed in. */
    Optional<AdminPrincipal> authenticate(HttpServletRequest request);

    /** Answers a request that {@link #authenticate} refused. */
    void deny(HttpServletRequest request, HttpServletResponse response) throws IOException;
}

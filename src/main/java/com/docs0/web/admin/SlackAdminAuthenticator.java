package com.docs0.web.admin;

import com.docs0.config.Docs0Properties;
import com.docs0.slack.SlackMessengers;
import com.docs0.tenant.AdminEmails;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.web.slack.MultiTenantCondition;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Multi-tenant sign-in: the admin signed in with Slack ({@link AdminLoginController}) and their server-side session
 * holds the one tenant they administer. It is consulted first. Only a request that carries the operator's HTTP Basic
 * password falls through to the pilot sign-in; the loopback shortcut of the pilot is off in this mode.
 *
 * <p>A suspended tenant's admins stay signed in but may only see {@code /admin/suspended} and sign out.
 */
@Component
@Primary
@Conditional(MultiTenantCondition.class)
public class SlackAdminAuthenticator implements AdminAuthenticator {

    /** Session attribute holding the {@link AdminPrincipal} established at sign-in. */
    public static final String SESSION_ATTRIBUTE = "docs0.slackAdmin";
    static final String SUSPENDED_PAGE = "/admin/suspended";
    static final String LOGOUT_PAGE = "/admin/logout";
    private static final String SUSPENDED_ATTRIBUTE = "docs0.suspendedAdmin";
    private static final String STATE_ATTRIBUTE = "docs0.slackAdminState";
    /** How often a session is checked again against the admin list and Slack. */
    static final Duration RECHECK_EVERY = Duration.ofMinutes(5);
    /** How long the last good check is trusted when Slack cannot be reached, after it became due. */
    static final Duration GRACE = Duration.ofMinutes(15);
    /** A session ends this long after sign-in, however busy it is. */
    static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);

    /** When the session signed in, was last checked and last passed a check. */
    record SessionState(Instant signedInAt, Instant checkedAt, Instant lastGoodAt) {}

    private final Docs0Properties props;
    private final TenantService tenants;
    private final AdminAuthenticator operator;
    private final SlackMessengers messengers;
    private final Clock clock;

    public SlackAdminAuthenticator(Docs0Properties props, TenantService tenants,
            @Qualifier("localAdminAuthenticator") AdminAuthenticator operator, SlackMessengers messengers,
            Clock clock) {
        this.props = props;
        this.tenants = tenants;
        this.operator = operator;
        this.messengers = messengers;
        this.clock = clock;
    }

    /** Starts the signed-in state of a new session. */
    public static void signIn(HttpSession session, AdminPrincipal principal, Instant now) {
        session.setAttribute(SESSION_ATTRIBUTE, principal);
        session.setAttribute(STATE_ATTRIBUTE, new SessionState(now, now, now));
    }

    @Override
    public Optional<AdminPrincipal> authenticate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof AdminPrincipal principal) {
            if (!stillAllowed(session, principal)) {
                session.invalidate();
                return Optional.empty();
            }
            if (tenants.isActive(principal.tenantId())) {
                return Optional.of(principal);
            }
            // Suspended (or gone): nothing but the explanation and the way out.
            String path = request.getRequestURI();
            if (SUSPENDED_PAGE.equals(path) || LOGOUT_PAGE.equals(path)) {
                return Optional.of(principal);
            }
            request.setAttribute(SUSPENDED_ATTRIBUTE, Boolean.TRUE);
            return Optional.empty();
        }
        if (props.adminPasswordSet() && request.getHeader("Authorization") != null) {
            return operator.authenticate(request);
        }
        return Optional.empty();
    }

    /**
     * False when the session is older than its absolute lifetime, or the person may no longer administer the
     * tenant. The admin list and Slack are asked at most every few minutes; when Slack cannot be reached the last
     * good answer is kept for a short grace time and then the session ends.
     */
    private boolean stillAllowed(HttpSession session, AdminPrincipal principal) {
        Instant now = clock.instant();
        SessionState state = session.getAttribute(STATE_ATTRIBUTE) instanceof SessionState s ? s : null;
        if (state == null || state.signedInAt().plus(ABSOLUTE_LIFETIME).isBefore(now)) {
            return false;
        }
        boolean withinGrace = state.lastGoodAt().plus(RECHECK_EVERY).plus(GRACE).isAfter(now);
        if (state.checkedAt().plus(RECHECK_EVERY).isAfter(now)) {
            return withinGrace;
        }
        Boolean allowed = mayAdminister(principal);
        if (allowed == null) {
            // Slack could not say. Keep the last good answer for the grace time only.
            session.setAttribute(STATE_ATTRIBUTE, new SessionState(state.signedInAt(), now, state.lastGoodAt()));
            return withinGrace;
        }
        if (!allowed) {
            return false;
        }
        session.setAttribute(STATE_ATTRIBUTE, new SessionState(state.signedInAt(), now, now));
        return true;
    }

    /** True or false when known, null when Slack could not be reached. */
    private Boolean mayAdminister(AdminPrincipal principal) {
        Tenant tenant = tenants.findById(principal.tenantId()).orElse(null);
        if (tenant == null) {
            return false;
        }
        if (principal.email() != null && AdminEmails.contains(tenant.adminEmails(), principal.email())) {
            return true;
        }
        if (principal.slackUserId() == null) {
            return false;
        }
        try {
            return messengers.forTenant(principal.tenantId()).isWorkspaceAdmin(principal.slackUserId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean browser = "GET".equals(request.getMethod()) && wantsHtml(request);
        if (request.getAttribute(SUSPENDED_ATTRIBUTE) != null) {
            if (browser) {
                response.sendRedirect(SUSPENDED_PAGE);
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }
        if (props.adminPasswordSet() && request.getHeader("Authorization") != null) {
            operator.deny(request, response);
        } else if (browser) {
            response.sendRedirect("/admin/login");
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private static boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}

package com.docs0.web.admin;

import com.docs0.config.Docs0Properties;
import com.docs0.tenant.TenantService;
import com.docs0.web.slack.MultiTenantCondition;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
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

    private final Docs0Properties props;
    private final TenantService tenants;
    private final AdminAuthenticator operator;

    public SlackAdminAuthenticator(Docs0Properties props, TenantService tenants,
            @Qualifier("localAdminAuthenticator") AdminAuthenticator operator) {
        this.props = props;
        this.tenants = tenants;
        this.operator = operator;
    }

    @Override
    public Optional<AdminPrincipal> authenticate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof AdminPrincipal principal) {
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

package com.docs0.web.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackSignIn;
import com.docs0.slack.SlackWorkspaces;
import com.docs0.tenant.AdminEmails;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.web.admin.AdminGuard;
import com.docs0.web.admin.AdminPrincipal;
import com.docs0.web.admin.SlackAdminAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Sign in with Slack for each company's admins. The person is matched to a tenant by their Slack workspace, and may
 * enter when their email is on that tenant's admin list or Slack says they administer the workspace. The session
 * then carries the one {@link AdminPrincipal} they may act as.
 */
@Controller
public class AdminLoginController {

    static final String LOGIN_ATTRIBUTE = "docs0.slackLogin";
    static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    static final Duration IDLE_TIMEOUT = Duration.ofHours(8);

    private static final Logger log = LoggerFactory.getLogger(AdminLoginController.class);

    /** The state and nonce handed to Slack and when they were made. Kept in the browser's server-side session. */
    record PendingLogin(String state, String nonce, Instant created) {}

    private final Docs0Properties props;
    private final SlackSignIn signIn;
    private final TenantService tenants;
    private final SlackWorkspaces workspaces;
    private final SlackMessengers messengers;
    private final Clock clock;

    public AdminLoginController(Docs0Properties props, SlackSignIn signIn, TenantService tenants,
            SlackWorkspaces workspaces, SlackMessengers messengers, Clock clock) {
        this.props = props;
        this.signIn = signIn;
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.messengers = messengers;
        this.clock = clock;
    }

    @GetMapping("/admin/login")
    public Object login(HttpServletRequest request) {
        if (!props.multiTenant()) {
            return notEnabled();
        }
        PendingLogin pending = new PendingLogin(AdminGuard.randomToken(), AdminGuard.randomToken(), clock.instant());
        request.getSession().setAttribute(LOGIN_ATTRIBUTE, pending);
        RedirectView slack = new RedirectView(signIn.authorizeUrl(pending.state(), pending.nonce()));
        slack.setExpandUriTemplateVariables(false);
        slack.setExposeModelAttributes(false);
        return slack;
    }

    @GetMapping("/admin/login/callback")
    public Object callback(HttpServletRequest request, @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "error", required = false) String error) {
        if (!props.multiTenant()) {
            return notEnabled();
        }
        // The state is good for one callback only, whatever the outcome.
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(LOGIN_ATTRIBUTE);
        if (session != null) {
            session.removeAttribute(LOGIN_ATTRIBUTE);
        }
        if (!(expected instanceof PendingLogin pending) || !AdminGuard.sameValue(pending.state(), state)
                || pending.created().plus(LOGIN_LIFETIME).isBefore(clock.instant())) {
            return page(HttpStatus.BAD_REQUEST, false, "That sign-in link has expired",
                    "The sign-in link expired or was already used.", null, "/admin/login", "Sign in with Slack");
        }
        if (error != null || code == null || code.isBlank()) {
            return page(HttpStatus.OK, false, "Sign-in cancelled", "Slack sign-in was cancelled. Nothing changed.",
                    null, "/admin/login", "Sign in with Slack");
        }
        SlackSignIn.Identity who;
        try {
            who = signIn.exchange(code);
        } catch (IOException | RuntimeException e) {
            log.warn("Slack sign-in failed: {}", e instanceof IOException ? e.getMessage() : e.getClass().getName());
            return page(HttpStatus.BAD_GATEWAY, false, "Sign-in did not finish",
                    "Slack sign-in did not complete. Try again.", null, "/admin/login", "Sign in with Slack");
        }
        if (who.nonce() == null || !AdminGuard.sameValue(pending.nonce(), who.nonce())) {
            return page(HttpStatus.BAD_REQUEST, false, "Sign-in did not finish",
                    "Slack sign-in could not be checked. Try again.", null, "/admin/login", "Sign in with Slack");
        }
        Optional<Tenant> tenant = tenants.findBySlackTeamId(who.teamId());
        if (tenant.isEmpty()) {
            return page(HttpStatus.FORBIDDEN, false, "docs0 is not installed there",
                    "docs0 is not installed in your Slack workspace yet.", null, "/slack/install", "Add to Slack");
        }
        if (workspaces.forTenant(tenant.get().id()).isEmpty()) {
            return page(HttpStatus.FORBIDDEN, false, "docs0 was removed",
                    "docs0 was removed from your Slack workspace. Add it again to sign in.", null, "/slack/install",
                    "Add to Slack");
        }
        if (!mayAdminister(tenant.get(), who)) {
            log.info("Slack sign-in denied: tenant={} team={}", tenant.get().id(), who.teamId());
            return page(HttpStatus.FORBIDDEN, false, "You are not an admin here",
                    "You are not an admin of this workspace's docs0.",
                    "Ask a Slack workspace admin to sign in and add your email on the Start page.", null, null);
        }
        // A new session id at the moment of sign-in, so a session id planted beforehand is worthless.
        session = request.getSession(true);
        request.changeSessionId();
        session.setMaxInactiveInterval((int) IDLE_TIMEOUT.toSeconds());
        String name = who.name() == null ? "admin" : who.name();
        session.setAttribute(SlackAdminAuthenticator.SESSION_ATTRIBUTE, new AdminPrincipal(tenant.get().id(), name));
        log.info("Slack sign-in: tenant={}", tenant.get().id());
        return new RedirectView("/admin/start", true);
    }

    /** Signs out. A POST under /admin, so the guard has already checked the CSRF token. */
    @PostMapping("/admin/logout")
    public RedirectView logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return new RedirectView("/", true);
    }

    /** Email on the tenant's list, or a workspace admin or owner according to Slack. */
    private boolean mayAdminister(Tenant tenant, SlackSignIn.Identity who) {
        if (AdminEmails.contains(tenant.adminEmails(), who.email())) {
            return true;
        }
        if (who.userId() == null) {
            return false;
        }
        try {
            SlackMessenger messenger = messengers.forTenant(tenant.id());
            return messenger.isWorkspaceAdmin(who.userId());
        } catch (RuntimeException e) {
            log.info("Workspace admin check failed: tenant={}", tenant.id());
            return false;
        }
    }

    private static ModelAndView notEnabled() {
        return page(HttpStatus.NOT_FOUND, false, "Sign in with Slack is not enabled",
                "This docs0 is set up for a single company.", null, null, null);
    }

    private static ModelAndView page(HttpStatus status, boolean ok, String title, String message, String detail,
            String href, String text) {
        return SlackInstallController.page(status, ok, title, message, detail, href, text);
    }
}

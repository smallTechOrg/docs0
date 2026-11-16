package com.docs0.web.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.slack.SlackInstaller;
import com.docs0.slack.SlackOAuth;
import com.docs0.web.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Add to Slack. {@code /slack/install} sends the browser to Slack with a single-use state tied to this browser
 * session; {@code /slack/oauth/callback} checks the state, trades the code for the workspace's bot token and
 * installs docs0 for that workspace. Public pages: the installer has no docs0 account yet.
 */
@Controller
public class SlackInstallController {

    static final String STATE_ATTRIBUTE = "docs0.slackInstallState";
    static final Duration STATE_LIFETIME = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(SlackInstallController.class);

    /** The state handed to Slack and when it was made. Held in the browser's server-side session. */
    record PendingState(String value, Instant created) {}

    private final Docs0Properties props;
    private final SlackOAuth oauth;
    private final SlackInstaller installer;
    private final Clock clock;

    public SlackInstallController(Docs0Properties props, SlackOAuth oauth, SlackInstaller installer, Clock clock) {
        this.props = props;
        this.oauth = oauth;
        this.installer = installer;
        this.clock = clock;
    }

    @GetMapping("/slack/install")
    public Object install(HttpServletRequest request, Model model) {
        if (!props.multiTenant()) {
            return page(HttpStatus.NOT_FOUND, false, "Add to Slack is not enabled",
                    "This docs0 is set up for a single company, so it cannot be added to other workspaces.", null,
                    null, null);
        }
        String state = AdminGuard.randomToken();
        request.getSession().setAttribute(STATE_ATTRIBUTE, new PendingState(state, clock.instant()));
        RedirectView slack = new RedirectView(oauth.authorizeUrl(state));
        slack.setExpandUriTemplateVariables(false);
        slack.setExposeModelAttributes(false);
        return slack;
    }

    @GetMapping("/slack/oauth/callback")
    public Object callback(HttpServletRequest request, Model model,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "error", required = false) String error) {
        if (!props.multiTenant()) {
            return install(request, model);
        }
        // The state is good for one callback only, whatever the outcome.
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(STATE_ATTRIBUTE);
        if (session != null) {
            session.removeAttribute(STATE_ATTRIBUTE);
        }
        if (!(expected instanceof PendingState pending) || !AdminGuard.sameValue(pending.value(), state)
                || pending.created().plus(STATE_LIFETIME).isBefore(clock.instant())) {
            return page(HttpStatus.BAD_REQUEST, false, "That link has expired",
                    "The Add to Slack link expired or was already used.", null, "/slack/install", "Add to Slack");
        }
        if ("access_denied".equals(error)) {
            return page(HttpStatus.OK, true, "Nothing was installed",
                    "No problem: docs0 was not added to your workspace. You can add it whenever you are ready.",
                    null, "/slack/install", "Add to Slack");
        }
        if (error != null || code == null || code.isBlank()) {
            return page(HttpStatus.BAD_REQUEST, false, "The install did not finish",
                    "Slack did not complete the install. Try again.", null, "/slack/install", "Add to Slack");
        }
        if (!installer.canStoreTokens()) {
            log.warn("Slack install refused: DOCS0_ENCRYPTION_KEY is not set");
            return page(HttpStatus.SERVICE_UNAVAILABLE, false, "docs0 is not ready",
                    "This docs0 cannot store workspace tokens yet. Ask whoever runs it to finish the setup.", null,
                    null, null);
        }
        SlackInstaller.Result result;
        try {
            result = installer.install(oauth.exchange(code));
        } catch (IOException | RuntimeException e) {
            log.warn("Slack install failed: {}", e instanceof IOException ? e.getMessage() : e.getClass().getName());
            return page(HttpStatus.BAD_GATEWAY, false, "The install did not finish",
                    "Slack did not complete the install. Try again.", null, "/slack/install", "Add to Slack");
        }
        installer.greet(result);
        String team = result.teamName() == null ? "your workspace" : result.teamName();
        if (result.suspended()) {
            return page(HttpStatus.OK, true, "docs0 is installed",
                    "docs0 is installed in " + team + ", but this workspace is suspended.",
                    "docs0 will not answer questions until the operator lifts the suspension.", "/admin/login",
                    "Sign in");
        }
        return page(HttpStatus.OK, true, "docs0 is installed",
                "docs0 is installed in " + team + ". Next: sign in to connect your docs.", null, "/admin/login",
                "Sign in with Slack");
    }

    /** A small public page. Everything shown is plain text; nothing from the request or any token is echoed. */
    public static org.springframework.web.servlet.ModelAndView page(HttpStatus status, boolean ok, String title,
            String message, String detail, String linkHref, String linkText) {
        var view = new org.springframework.web.servlet.ModelAndView("public-message", status);
        view.addObject("ok", ok);
        view.addObject("title", title);
        view.addObject("message", message);
        view.addObject("detail", detail);
        view.addObject("linkHref", linkHref);
        view.addObject("linkText", linkText);
        view.addObject("secondHref", null);
        view.addObject("secondText", null);
        view.addObject("csrf", null);
        return view;
    }
}

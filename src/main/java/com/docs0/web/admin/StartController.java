package com.docs0.web.admin;

import com.docs0.budget.BudgetService;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackWorkspaces;
import com.docs0.tenant.AdminEmails;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor;
import com.docs0.web.StatusContributor.ComponentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Start page: a company's onboarding checklist and its admin list. Everything shown or changed belongs to the
 * signed-in admin's own tenant.
 */
@Controller
public class StartController {

    static final String PAGE = "/admin/start";

    /** One place to add knowledge: where to go, and how it is doing. */
    public record SourceRow(String name, String anchor, ComponentStatus status) {}

    private static final List<String[]> SOURCES = List.of(new String[] {"Google Drive", "drive"},
            new String[] {"Linear", "linear"}, new String[] {"Trello", "trello"}, new String[] {"Grain", "grain"},
            new String[] {"Slack history", "slack"}, new String[] {"Gmail", "gmail"});

    private final TenantService tenants;
    private final SlackWorkspaces workspaces;
    private final SlackMessengers messengers;
    private final BudgetService budget;
    private final List<StatusContributor> contributors;

    public StartController(TenantService tenants, SlackWorkspaces workspaces, SlackMessengers messengers,
            BudgetService budget, List<StatusContributor> contributors) {
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.messengers = messengers;
        this.budget = budget;
        this.contributors = contributors;
    }

    @GetMapping(PAGE)
    public String page(AdminPrincipal admin, HttpServletRequest request, Model model) {
        long tenantId = admin.tenantId();
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new IllegalStateException("Unknown tenant"));
        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenant.name());
        model.addAttribute("who", admin.displayName());

        Optional<SlackWorkspaces.Installation> installation = workspaces.forTenant(tenantId);
        model.addAttribute("slackTeam", installation.map(SlackWorkspaces.Installation::teamName).orElse(null));
        model.addAttribute("slackConnected", installation.isPresent());
        model.addAttribute("botName", installation.map(i -> botName(tenantId, i)).orElse(null));

        Map<String, ComponentStatus> byName = new HashMap<>();
        for (StatusContributor contributor : contributors) {
            try {
                ComponentStatus status = contributor.status(tenantId);
                byName.put(status.name(), status);
            } catch (RuntimeException e) {
                // That source simply shows as not set up.
            }
        }
        List<SourceRow> sources = new ArrayList<>();
        for (String[] source : SOURCES) {
            sources.add(new SourceRow(source[0], source[1],
                    byName.getOrDefault(source[0], ComponentStatus.off(source[0], "not set up"))));
        }
        model.addAttribute("sources", sources);

        List<String> admins = AdminEmails.parse(tenant.adminEmails());
        model.addAttribute("admins", admins);
        model.addAttribute("canAddAdmin", admins.size() < AdminEmails.MAX);
        model.addAttribute("maxAdmins", AdminEmails.MAX);

        BigDecimal spent = budget.spentThisMonth(tenantId);
        BigDecimal cap = budget.monthlyCap(tenantId);
        model.addAttribute("spent", spent);
        model.addAttribute("cap", cap);
        return "start";
    }

    @PostMapping(PAGE + "/admins/add")
    public RedirectView addAdmin(AdminPrincipal admin, @RequestParam("email") String email, RedirectAttributes flash) {
        String clean = email.strip().toLowerCase(Locale.ROOT);
        Tenant tenant = tenants.findById(admin.tenantId()).orElseThrow(() -> new IllegalStateException("Unknown tenant"));
        List<String> admins = AdminEmails.parse(tenant.adminEmails());
        if (!AdminEmails.valid(clean)) {
            flash.addFlashAttribute("error", "That does not look like an email address.");
        } else if (admins.contains(clean)) {
            flash.addFlashAttribute("notice", "That person is already an admin.");
        } else if (admins.size() >= AdminEmails.MAX) {
            flash.addFlashAttribute("error", "A company can have at most " + AdminEmails.MAX + " admin emails.");
        } else {
            admins.add(clean);
            tenants.setAdminEmails(admin.tenantId(), AdminEmails.join(admins));
            flash.addFlashAttribute("notice", "Admin added.");
        }
        return back();
    }

    @PostMapping(PAGE + "/admins/remove")
    public RedirectView removeAdmin(AdminPrincipal admin, @RequestParam("email") String email,
            RedirectAttributes flash) {
        Tenant tenant = tenants.findById(admin.tenantId()).orElseThrow(() -> new IllegalStateException("Unknown tenant"));
        List<String> admins = AdminEmails.parse(tenant.adminEmails());
        if (admins.remove(email.strip().toLowerCase(Locale.ROOT))) {
            tenants.setAdminEmails(admin.tenantId(), AdminEmails.join(admins));
            flash.addFlashAttribute("notice", "Admin removed.");
        }
        return back();
    }

    /** The page a suspended company's admins are sent to. */
    @GetMapping("/admin/suspended")
    public Object suspended(HttpServletRequest request) {
        var view = com.docs0.web.slack.SlackInstallController.page(org.springframework.http.HttpStatus.FORBIDDEN, false,
                "This workspace is suspended", "This workspace is suspended, so docs0 is not answering questions.",
                "Contact whoever runs docs0 to have it switched back on.", null, null);
        view.addObject("csrf", AdminGuard.csrfToken(request.getSession()));
        return view;
    }

    private String botName(long tenantId, SlackWorkspaces.Installation installation) {
        try {
            String name = messengers.forTenant(tenantId).userDisplayName(installation.botUserId());
            return name == null || name.isBlank() ? "docs0" : name;
        } catch (RuntimeException e) {
            return "docs0";
        }
    }

    private static RedirectView back() {
        RedirectView view = new RedirectView(PAGE + "#admins", true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}

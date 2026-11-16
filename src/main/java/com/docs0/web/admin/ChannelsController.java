package com.docs0.web.admin;

import com.docs0.settings.ChannelSettings;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/** The Channels page: which channels docs0 may answer repeat questions in. Nothing is on by default. */
@Controller
public class ChannelsController {

    static final String PAGE = "/admin/channels";

    /** One channel in the list. */
    public record Row(String id, String name, boolean isPrivate, boolean member, boolean proactive) {}

    private final TenantService tenants;
    private final SlackMessengers messengers;
    private final ChannelSettings settings;
    private final int maxPerHour;

    public ChannelsController(TenantService tenants, SlackMessengers messengers, ChannelSettings settings,
            @Value("${docs0.proactive.max-per-hour:3}") int maxPerHour) {
        this.tenants = tenants;
        this.messengers = messengers;
        this.settings = settings;
        this.maxPerHour = maxPerHour;
    }

    @GetMapping(PAGE)
    public String page(AdminPrincipal admin, HttpServletRequest request, Model model) {
        long tenantId = admin.tenantId();
        List<Row> rows = null;
        String error = null;
        try {
            Set<String> on = settings.proactiveChannels(tenantId);
            rows = new ArrayList<>();
            for (ChannelInfo c : messengers.forTenant(admin.tenantId()).listChannels()) {
                rows.add(new Row(c.id(), c.name(), c.isPrivate(), c.member(), on.contains(c.id())));
            }
            rows.sort(Comparator.comparing((Row r) -> r.name().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            error = SlackHistoryPanel.describe(e);
        }
        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenants.name(admin.tenantId()));
        model.addAttribute("channels", rows);
        model.addAttribute("loadError", error);
        model.addAttribute("maxPerHour", maxPerHour);
        return "channels";
    }

    @PostMapping(PAGE)
    public RedirectView save(AdminPrincipal admin, @RequestParam(name = "proactive", required = false) List<String> proactive,
            RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        List<ChannelSettings.Channel> listed = new ArrayList<>();
        try {
            for (ChannelInfo c : messengers.forTenant(admin.tenantId()).listChannels()) {
                listed.add(new ChannelSettings.Channel(c.id(), c.name()));
            }
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", "Could not ask Slack for its channels, so nothing was saved. Try again.");
            return to();
        }
        Set<String> wanted = proactive == null ? Set.of() : Set.copyOf(proactive);
        settings.saveProactive(tenantId, listed, wanted);
        long count = listed.stream().filter(c -> wanted.contains(c.id())).count();
        flash.addFlashAttribute("notice", count == 0
                ? "Saved. docs0 will not answer unprompted in any channel."
                : "Saved. docs0 may answer repeat questions in " + count + (count == 1 ? " channel." : " channels."));
        return to();
    }

    private static RedirectView to() {
        RedirectView view = new RedirectView(PAGE, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}

package com.docs0.web.admin;

import com.docs0.feedback.Correction;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.UnhappyAnswer;
import com.docs0.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Corrections page: pending corrections to approve, edit or reject, answers that got a
 * thumbs down, and the corrections already reviewed. Every change is a form POST and a redirect.
 */
@Controller
public class CorrectionsController {

    static final String PAGE = "/admin/corrections";
    static final int LIST_LIMIT = 100;
    static final int REVIEWED_LIMIT = 50;
    static final int MAX_EDIT_CHARS = 2000;
    static final int ANSWER_PREVIEW_CHARS = 140;

    private static final List<String> TABS = List.of("pending", "unhappy", "reviewed");
    private static final Map<String, String> NOTICES = Map.of(
            "approved", "Approved. docs0 will use it in its answers from now on.",
            "rejected", "Rejected. docs0 will not use it.",
            "unapproved", "Taken out of use. docs0 will no longer use it.",
            "gone", "That correction was already reviewed.");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** A correction waiting for review. {@code answerPreview} is the start of the original answer. */
    public record PendingRow(long id, String author, boolean dm, String question, String answerPreview,
            String answer, String fact, String when) {}

    public record UnhappyRow(String asker, String question, String answer, List<FeedbackRepository.CitedSource> sources,
            int thumbsDown, String when) {}

    public record ReviewedRow(long id, boolean approved, String author, boolean dm, String question, String fact,
            String when) {}

    private final TenantService tenants;
    private final CorrectionRepository corrections;
    private final FeedbackRepository feedback;

    public CorrectionsController(TenantService tenants, CorrectionRepository corrections, FeedbackRepository feedback) {
        this.tenants = tenants;
        this.corrections = corrections;
        this.feedback = feedback;
    }

    @GetMapping(PAGE)
    public String page(HttpServletRequest request,
            @RequestParam(name = "tab", required = false) String tab,
            @RequestParam(name = "notice", required = false) String notice, Model model) {
        long tenantId = tenants.current().id();
        String current = tab != null && TABS.contains(tab) ? tab : "pending";

        List<PendingRow> pending = corrections.pending(tenantId, LIST_LIMIT).stream()
                .map(c -> new PendingRow(c.id(), c.authorName(), c.fromDm(), c.question(),
                        truncate(c.originalAnswer(), ANSWER_PREVIEW_CHARS), c.originalAnswer(), c.fact(),
                        TIME.format(c.createdAt())))
                .toList();
        List<UnhappyRow> unhappy = feedback.unhappy(tenantId, LIST_LIMIT).stream().map(CorrectionsController::row)
                .toList();
        List<ReviewedRow> reviewed = corrections.reviewed(tenantId, REVIEWED_LIMIT).stream()
                .map(c -> new ReviewedRow(c.id(), c.status() == Correction.Status.APPROVED, c.authorName(),
                        c.fromDm(), c.question(), c.fact(), TIME.format(c.reviewedAt())))
                .toList();

        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenants.current().name());
        model.addAttribute("tab", current);
        model.addAttribute("notice", notice == null ? null : NOTICES.get(notice));
        model.addAttribute("pending", pending);
        model.addAttribute("unhappy", unhappy);
        model.addAttribute("reviewed", reviewed);
        return "corrections";
    }

    @PostMapping(PAGE + "/{id}/approve")
    public RedirectView approve(@PathVariable long id,
            @RequestParam(name = "text", required = false) String text) {
        String edited = text == null ? null : text.length() <= MAX_EDIT_CHARS ? text : text.substring(0, MAX_EDIT_CHARS);
        boolean done = corrections.approve(tenants.current().id(), id, edited);
        return to("pending", done ? "approved" : "gone");
    }

    @PostMapping(PAGE + "/{id}/reject")
    public RedirectView reject(@PathVariable long id) {
        return to("pending", corrections.reject(tenants.current().id(), id) ? "rejected" : "gone");
    }

    @PostMapping(PAGE + "/{id}/unapprove")
    public RedirectView unapprove(@PathVariable long id) {
        return to("reviewed", corrections.unapprove(tenants.current().id(), id) ? "unapproved" : "gone");
    }

    private static UnhappyRow row(UnhappyAnswer a) {
        // A DM's asker stays anonymous; a channel question shows who asked.
        return new UnhappyRow(a.dm() ? "DM" : a.askerSlackId(), a.question(), a.answer() == null ? "" : a.answer(),
                a.citations(), a.thumbsDown(), TIME.format(a.createdAt()));
    }

    /** Text cut to at most {@code max} characters, with an ellipsis when something was left out. */
    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
    }

    private static RedirectView to(String tab, String notice) {
        RedirectView view = new RedirectView(PAGE + "?tab=" + tab + "&notice=" + notice, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}

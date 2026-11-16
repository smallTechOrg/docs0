package com.docs0.web.admin;

import com.docs0.conversation.InteractionRepository;
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
import java.util.Objects;
import java.util.Set;
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
    /** Shown in place of an answer that used a private source (a private channel, a mailbox). */
    public static final String PRIVATE_NOTICE = "This answer used a private source, so it isn't shown here.";

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
            String answer, boolean redacted, String fact, String when) {}

    /** {@code redacted}: the answer used a private source, so its text, sources and quotes are not carried. */
    public record UnhappyRow(String asker, String question, String answer, List<FeedbackRepository.CitedSource> sources,
            boolean redacted, int thumbsDown, String when) {}

    public record ReviewedRow(long id, boolean approved, String author, boolean dm, String question, String fact,
            String when) {}

    private final TenantService tenants;
    private final CorrectionRepository corrections;
    private final FeedbackRepository feedback;
    private final InteractionRepository interactions;

    public CorrectionsController(TenantService tenants, CorrectionRepository corrections, FeedbackRepository feedback,
            InteractionRepository interactions) {
        this.tenants = tenants;
        this.corrections = corrections;
        this.feedback = feedback;
        this.interactions = interactions;
    }

    @GetMapping(PAGE)
    public String page(HttpServletRequest request,
            @RequestParam(name = "tab", required = false) String tab,
            @RequestParam(name = "notice", required = false) String notice, Model model) {
        long tenantId = tenants.current().id();
        String current = tab != null && TABS.contains(tab) ? tab : "pending";

        List<Correction> waiting = corrections.pending(tenantId, LIST_LIMIT);
        // An answer that used a private source is never shown here, whoever reads this page.
        Set<Long> privateAnswers = interactions.usedRestricted(tenantId, waiting.stream()
                .map(Correction::interactionId).filter(Objects::nonNull).toList());
        List<PendingRow> pending = waiting.stream()
                .map(c -> {
                    boolean redacted = c.interactionId() != null && privateAnswers.contains(c.interactionId());
                    String answer = redacted ? "" : c.originalAnswer();
                    return new PendingRow(c.id(), c.authorName(), c.fromDm(), c.question(),
                            truncate(answer, ANSWER_PREVIEW_CHARS), answer == null ? "" : answer, redacted, c.fact(),
                            TIME.format(c.createdAt()));
                })
                .toList();
        List<UnhappyAnswer> disliked = feedback.unhappy(tenantId, LIST_LIMIT);
        Set<Long> privateUnhappy = interactions.usedRestricted(tenantId,
                disliked.stream().map(UnhappyAnswer::interactionId).toList());
        List<UnhappyRow> unhappy = disliked.stream()
                .map(a -> row(a, privateUnhappy.contains(a.interactionId()))).toList();
        List<ReviewedRow> reviewed = corrections.reviewed(tenantId, REVIEWED_LIMIT).stream()
                .map(c -> new ReviewedRow(c.id(), c.status() == Correction.Status.APPROVED, c.authorName(),
                        c.fromDm(), c.question(), c.fact(), TIME.format(c.reviewedAt())))
                .toList();

        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenants.current().name());
        model.addAttribute("tab", current);
        model.addAttribute("privateNotice", PRIVATE_NOTICE);
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

    private static UnhappyRow row(UnhappyAnswer a, boolean redacted) {
        // A DM's asker stays anonymous; a channel question shows who asked.
        return new UnhappyRow(a.dm() ? "DM" : a.askerSlackId(), a.question(),
                redacted || a.answer() == null ? "" : a.answer(), redacted ? List.of() : a.citations(), redacted,
                a.thumbsDown(), TIME.format(a.createdAt()));
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

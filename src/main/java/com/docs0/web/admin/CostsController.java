package com.docs0.web.admin;

import com.docs0.budget.BudgetService;
import com.docs0.budget.CostReport;
import com.docs0.conversation.InteractionRepository;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The Costs page: this month against the cap, a table per day and the dearest questions. */
@Controller
public class CostsController {

    static final String PAGE = "/admin/costs";
    static final int DAYS = 30;
    static final int TOP = 10;
    static final int MAX_QUESTION_CHARS = 140;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public record DayRow(String day, long questions, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    public record TopRow(String question, boolean dm, boolean redacted, String when, BigDecimal costUsd) {}

    private final TenantService tenants;
    private final BudgetService budget;
    private final CostReport report;
    private final InteractionRepository interactions;

    public CostsController(TenantService tenants, BudgetService budget, CostReport report,
            InteractionRepository interactions) {
        this.tenants = tenants;
        this.budget = budget;
        this.report = report;
        this.interactions = interactions;
    }

    @GetMapping(PAGE)
    public String page(Model model) {
        long tenantId = tenants.current().id();
        BigDecimal spent = budget.spentThisMonth(tenantId);
        BigDecimal other = budget.otherSpentThisMonth(tenantId);
        BigDecimal cap = budget.monthlyCap();
        int percent = cap == null ? 0
                : spent.multiply(BigDecimal.valueOf(100)).divide(cap, 0, java.math.RoundingMode.DOWN)
                        .min(BigDecimal.valueOf(100)).intValue();

        List<DayRow> days = report.perDay(tenantId, DAYS).stream()
                .map(d -> new DayRow(DAY.format(d.day()), d.questions(), d.inputTokens(), d.outputTokens(),
                        d.costUsd()))
                .toList();
        var expensive = report.topQuestionsThisMonth(tenantId, TOP);
        // A question whose answer used a private source is not shown either.
        Set<Long> privateAnswers = interactions.usedRestricted(tenantId,
                expensive.stream().map(CostReport.Expensive::interactionId).toList());
        List<TopRow> top = expensive.stream()
                .map(e -> {
                    boolean redacted = privateAnswers.contains(e.interactionId());
                    return new TopRow(e.dm() || redacted ? null
                            : CorrectionsController.truncate(e.question(), MAX_QUESTION_CHARS),
                            e.dm(), redacted, TIME.format(e.createdAt()), e.costUsd());
                })
                .toList();

        model.addAttribute("tenantName", tenants.current().name());
        model.addAttribute("spent", spent);
        model.addAttribute("otherSpent", other);
        model.addAttribute("questionSpent", spent.subtract(other));
        model.addAttribute("otherSpent30", report.otherSpend(tenantId, DAYS));
        model.addAttribute("cap", cap);
        model.addAttribute("percent", percent);
        model.addAttribute("days", days);
        model.addAttribute("top", top);
        model.addAttribute("dayCount", DAYS);
        return "costs";
    }
}

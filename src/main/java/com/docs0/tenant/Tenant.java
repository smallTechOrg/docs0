package com.docs0.tenant;

import java.math.BigDecimal;

/**
 * One company served by this deployment.
 *
 * @param monthlyBudgetUsd the tenant's own monthly cap, or null to use the global default
 * @param status           ACTIVE or SUSPENDED
 * @param adminEmails      comma-separated; who may sign in to this tenant's admin pages (Phase 7b)
 */
public record Tenant(long id, String slug, String name, String slackTeamId, BigDecimal monthlyBudgetUsd,
                     String status, String adminEmails) {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    public boolean active() {
        return !SUSPENDED.equals(status);
    }
}

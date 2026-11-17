package com.docs0.agent;

import java.time.Instant;
import java.util.List;

/** Where the agent finds approved corrections for a question. The real one is a Postgres full-text search. */
public interface CorrectionSource {

    /** Up to {@code limit} approved corrections of the tenant that match the question, best match first. */
    List<ApprovedCorrection> find(long tenantId, String question, int limit);

    /**
     * An approved correction. {@code fact} is the admin's edited text when there is one.
     * {@code authorName} is already "a teammate" for a correction made in a DM, and
     * {@code question} is null for one: a DM's wording is never passed on.
     */
    record ApprovedCorrection(long id, String question, String fact, String authorName, Instant correctedAt) {}
}

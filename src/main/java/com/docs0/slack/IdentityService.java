package com.docs0.slack;

import com.docs0.slack.SlackMessenger.SlackCallException;
import com.docs0.slack.SlackMessenger.SlackUser;
import com.google.gson.Gson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Who Slack users are, cached in the {@code identity} table and refreshed lazily when an entry is
 * older than a day. Used to build answer audiences (drop bots and deactivated users) and to name
 * people in indexed Slack history. A user that cannot be looked up is simply absent from the result:
 * callers then treat them as an ordinary person known by Slack id only, which is the cautious choice.
 */
@Service
public class IdentityService {

    /** A cached Slack user. {@code email} is null when the app may not read emails. */
    public record Person(String slackId, String email, String displayName, boolean bot, boolean deleted) {}

    static final Duration MAX_AGE = Duration.ofHours(24);
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(20);
    private static final int PARALLEL_LOOKUPS = 8;
    private static final String EMAIL_SCOPE = "users:read.email";

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final Gson GSON = new Gson();

    private final JdbcClient jdbc;
    private final SlackMessengers messengers;
    /** Tenants whose Slack app was seen lacking the email scope, and those already warned about. Per tenant. */
    private final Set<Long> missingScopeSeen = ConcurrentHashMap.newKeySet();
    private final Set<Long> warned = ConcurrentHashMap.newKeySet();

    public IdentityService(JdbcClient jdbc, SlackMessengers messengers) {
        this.jdbc = jdbc;
        this.messengers = messengers;
    }

    /** One person, from the cache or from Slack. Empty when Slack cannot say and nothing is cached. */
    public java.util.Optional<Person> person(long tenantId, String slackId) {
        return java.util.Optional.ofNullable(resolve(tenantId, Set.of(slackId), DEFAULT_BUDGET).get(slackId));
    }

    /** The name people know a user by, or the user id when it cannot be found. */
    public String displayName(long tenantId, String slackId) {
        return person(tenantId, slackId).map(Person::displayName).filter(n -> n != null && !n.isBlank())
                .orElse(slackId);
    }

    /**
     * The people behind these user ids, keyed by id. Fresh cache entries are used as they are; the
     * rest are asked of Slack a few at a time until {@code budget} is spent, so one huge channel
     * cannot stall an answer. If Slack fails for a user, a stale cache entry is used; if there is none
     * the user is left out of the result. The cache fills up over successive calls.
     */
    public Map<String, Person> resolve(long tenantId, Collection<String> slackIds, Duration budget) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : slackIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.strip());
            }
        }
        Map<String, Person> people = new HashMap<>();
        if (ids.isEmpty()) {
            return people;
        }
        Instant freshAfter = Instant.now().minus(MAX_AGE);
        List<String> stale = new ArrayList<>();
        Map<String, Person> staleCopies = new HashMap<>();
        for (Row row : load(tenantId, ids)) {
            if (row.updatedAt().isAfter(freshAfter)) {
                people.put(row.person().slackId(), row.person());
            } else {
                staleCopies.put(row.person().slackId(), row.person());
            }
        }
        for (String id : ids) {
            if (!people.containsKey(id)) {
                stale.add(id);
            }
        }
        if (!stale.isEmpty()) {
            Map<String, Person> refreshed = refresh(tenantId, stale, budget);
            people.putAll(refreshed);
            for (String id : stale) {
                if (!people.containsKey(id) && staleCopies.containsKey(id)) {
                    people.put(id, staleCopies.get(id));
                }
            }
        }
        return people;
    }

    /** True when the Slack app cannot read emails, so people are matched by Slack user id only. */
    public boolean emailScopeMissing(long tenantId) {
        if (missingScopeSeen.contains(tenantId)) {
            return true;
        }
        Set<String> scopes;
        try {
            scopes = messengers.forTenant(tenantId).botScopes();
        } catch (RuntimeException e) {
            return false;
        }
        boolean missing = !scopes.isEmpty() && !scopes.contains(EMAIL_SCOPE);
        if (missing) {
            warnOnce(tenantId);
        }
        return missing;
    }

    private Map<String, Person> refresh(long tenantId, List<String> ids, Duration budget) {
        Map<String, Person> refreshed = new java.util.concurrent.ConcurrentHashMap<>();
        Instant deadline = Instant.now().plus(budget);
        SlackMessenger messenger;
        try {
            messenger = messengers.forTenant(tenantId);
        } catch (RuntimeException e) {
            log.debug("No Slack workspace to look users up in: tenant={}", tenantId);
            return refreshed;
        }
        Semaphore slots = new Semaphore(PARALLEL_LOOKUPS);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String id : ids) {
                workers.execute(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        if (Instant.now().isAfter(deadline)) {
                            return;
                        }
                        SlackUser user = messenger.userInfo(id);
                        Person person = new Person(id, user.email(), user.displayName(), user.bot(), user.deleted());
                        save(tenantId, person);
                        refreshed.put(id, person);
                    } catch (SlackCallException e) {
                        if ("missing_scope".equals(e.code())) {
                            missingScopeSeen.add(tenantId);
                            warnOnce(tenantId);
                        }
                        log.debug("User lookup failed: code={}", e.code());
                    } catch (RuntimeException e) {
                        log.debug("User lookup failed: {}", e.getClass().getName());
                    } finally {
                        slots.release();
                    }
                });
            }
        }
        return refreshed;
    }

    private void warnOnce(long tenantId) {
        if (warned.add(tenantId)) {
            log.warn("The Slack app of tenant {} cannot read email addresses (scope {} missing). People are matched by "
                    + "Slack user id only. Add the scope to the app and reinstall it.", tenantId, EMAIL_SCOPE);
        }
    }

    private record Row(Person person, Instant updatedAt) {}

    private List<Row> load(long tenantId, Set<String> ids) {
        return jdbc.sql("""
                        SELECT slack_user_id, email, display_name, is_bot, deleted, updated_at FROM identity
                        WHERE tenant_id = :tenant
                          AND slack_user_id IN (SELECT jsonb_array_elements_text(CAST(:ids AS jsonb)))
                        """)
                .param("tenant", tenantId)
                .param("ids", GSON.toJson(ids))
                .query((rs, n) -> row(rs))
                .list();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(new Person(rs.getString("slack_user_id"), rs.getString("email"), rs.getString("display_name"),
                rs.getBoolean("is_bot"), rs.getBoolean("deleted")),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private void save(long tenantId, Person person) {
        jdbc.sql("""
                        INSERT INTO identity (tenant_id, slack_user_id, email, display_name, is_bot, deleted, updated_at)
                        VALUES (:tenant, :id, :email, :name, :bot, :deleted, :now)
                        ON CONFLICT (tenant_id, slack_user_id) DO UPDATE SET
                            email = EXCLUDED.email, display_name = EXCLUDED.display_name, is_bot = EXCLUDED.is_bot,
                            deleted = EXCLUDED.deleted, updated_at = EXCLUDED.updated_at
                        """)
                .param("tenant", tenantId)
                .param("id", person.slackId())
                .param("email", person.email() == null ? null : person.email().toLowerCase(java.util.Locale.ROOT))
                .param("name", person.displayName())
                .param("bot", person.bot())
                .param("deleted", person.deleted())
                .param("now", Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }
}

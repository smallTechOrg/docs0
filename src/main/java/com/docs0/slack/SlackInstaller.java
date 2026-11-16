package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.tenant.AdminEmails;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens after Slack's OAuth callback: the workspace becomes a tenant (or its installation is renewed) and the
 * installer gets a welcome message. A tenant that is SUSPENDED stays suspended; reinstalling never reactivates it.
 */
@Service
public class SlackInstaller {

    /** What the install did. {@code installerUserId} is carried only to greet the installer. */
    public record Result(long tenantId, String teamName, boolean created, boolean suspended, String installerUserId) {}

    private static final Logger log = LoggerFactory.getLogger(SlackInstaller.class);
    private static final int MAX_SLUG = 40;

    private final TenantService tenants;
    private final SlackWorkspaces workspaces;
    private final SlackMessengers messengers;
    private final TokenCipher cipher;
    private final Docs0Properties props;

    public SlackInstaller(TenantService tenants, SlackWorkspaces workspaces, SlackMessengers messengers,
            TokenCipher cipher, Docs0Properties props) {
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.messengers = messengers;
        this.cipher = cipher;
        this.props = props;
    }

    /** False when bot tokens cannot be stored (no DOCS0_ENCRYPTION_KEY): installing would lose the token. */
    public boolean canStoreTokens() {
        return cipher.available();
    }

    /**
     * Creates the workspace's tenant, or finds the one that already has this workspace, and stores the encrypted
     * installation. Fails with {@link IllegalStateException} when the workspace belongs to a tenant that cannot take it.
     */
    @Transactional
    public Result install(SlackOAuth.Installed installed) {
        Optional<Tenant> existing = tenants.findBySlackTeamId(installed.teamId());
        boolean created = existing.isEmpty();
        Tenant tenant = existing.orElseGet(() -> createTenant(installed.teamName()));
        workspaces.register(tenant.id(), installed.teamId(), installed.teamName(), installed.accessToken(),
                installed.botUserId(), installed.installerUserId(), installed.scopes());
        log.info("Slack install: tenant={} team={} created={}", tenant.id(), installed.teamId(), created);
        return new Result(tenant.id(), installed.teamName(), created, !tenant.active(), installed.installerUserId());
    }

    /**
     * Best effort, after the install has committed: the installer becomes an admin when their email is readable and
     * the tenant has none yet, and gets a welcome message. Neither failing can fail the install.
     */
    public void greet(Result result) {
        if (result.installerUserId() == null || result.suspended()) {
            return;
        }
        try {
            SlackMessenger messenger = messengers.forTenant(result.tenantId());
            Optional<Tenant> tenant = tenants.findById(result.tenantId());
            if (tenant.isPresent() && AdminEmails.parse(tenant.get().adminEmails()).isEmpty()) {
                String email = messenger.userInfo(result.installerUserId()).email();
                if (email != null && AdminEmails.valid(email.toLowerCase(Locale.ROOT))) {
                    tenants.setAdminEmails(result.tenantId(), email.strip().toLowerCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException e) {
            log.info("Installer email not read: tenant={} reason={}", result.tenantId(), reason(e));
        }
        try {
            SlackMessenger messenger = messengers.forTenant(result.tenantId());
            String channel = messenger.openDm(result.installerUserId());
            messenger.postMessage(channel, null, welcome(), null);
        } catch (RuntimeException e) {
            log.info("Welcome message not sent: tenant={} reason={}", result.tenantId(), reason(e));
        }
    }

    String welcome() {
        return "Welcome to docs0! Next step: <" + props.publicBaseUrl() + "/admin/start|sign in to connect your docs>.\n"
                + "To use me, mention @docs0 in a channel I have been invited to, or send me a direct message.";
    }

    private Tenant createTenant(String teamName) {
        String name = teamName == null || teamName.isBlank() ? "Workspace" : teamName.strip();
        String base = slug(name);
        for (int attempt = 1; attempt <= 50; attempt++) {
            String candidate = attempt == 1 ? base : base + "-" + attempt;
            if (tenants.findBySlug(candidate).isPresent()) {
                continue;
            }
            return tenants.create(candidate, name);
        }
        throw new IllegalStateException("No free name for the new company");
    }

    /** A URL-safe slug: lower-case letters, digits and single hyphens. */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "workspace" : slug;
    }

    private static String reason(RuntimeException e) {
        return e instanceof SlackMessenger.SlackCallException slack ? slack.code() : e.getClass().getSimpleName();
    }
}

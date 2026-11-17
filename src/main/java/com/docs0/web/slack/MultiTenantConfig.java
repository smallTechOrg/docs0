package com.docs0.web.slack;

import com.docs0.config.Docs0Properties;
import jakarta.servlet.SessionCookieConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** Start-up checks and the session cookie settings for multi-tenant mode. Does nothing with the mode off. */
@Configuration
public class MultiTenantConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantConfig.class);

    private final Docs0Properties props;

    public MultiTenantConfig(Docs0Properties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (!props.multiTenant()) {
            return;
        }
        log.info("Multi-tenant mode: companies install docs0 with Add to Slack; Slack events arrive over HTTP");
        if (!props.publicUrlIsHttps()) {
            log.warn("DOCS0_PUBLIC_URL is not an https URL. Slack requires https for events, interactivity and "
                    + "sign-in redirects, so Add to Slack and Sign in with Slack will not work until it is.");
        }
    }

    /** Admin sessions: HttpOnly, SameSite=Lax, Secure when the public URL is https. */
    @Bean
    ServletContextInitializer adminSessionCookie() {
        return servletContext -> {
            if (props.multiTenant()) {
                configure(servletContext.getSessionCookieConfig(), props.publicUrlIsHttps());
            }
        };
    }

    static void configure(SessionCookieConfig cookie, boolean secure) {
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", "Lax");
    }
}

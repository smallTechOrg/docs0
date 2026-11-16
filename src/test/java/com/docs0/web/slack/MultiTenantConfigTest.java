package com.docs0.web.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.config.Docs0Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockServletContext;

/** Session cookie flags and the start-up warning for a public URL that is not https. */
@ExtendWith(OutputCaptureExtension.class)
class MultiTenantConfigTest {

    private static Docs0Properties props(String publicUrl, boolean multi) {
        return new Docs0Properties(null, null, null,
                multi ? new Docs0Properties.Slack("", "", "id", "secret", "sign") : new Docs0Properties.Slack("", ""),
                null, null, "", publicUrl, "");
    }

    @Test
    void sessionCookiesAreHttpOnlySameSiteLaxAndSecureOnHttps() throws Exception {
        MockServletContext context = new MockServletContext();

        new MultiTenantConfig(props("https://docs0.example", true)).adminSessionCookie().onStartup(context);

        var cookie = context.getSessionCookieConfig();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void sessionCookiesAreNotSecureOnPlainHttpButStillHttpOnlyAndLax() throws Exception {
        MockServletContext context = new MockServletContext();

        new MultiTenantConfig(props("http://localhost:8080", true)).adminSessionCookie().onStartup(context);

        assertThat(context.getSessionCookieConfig().isSecure()).isFalse();
        assertThat(context.getSessionCookieConfig().isHttpOnly()).isTrue();
        assertThat(context.getSessionCookieConfig().getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void warnsAtStartupWhenTheModeIsOnAndThePublicUrlIsNotHttps(CapturedOutput output) {
        new MultiTenantConfig(props("http://localhost:8080", true)).announce();

        assertThat(output.getAll()).contains("WARN").contains("DOCS0_PUBLIC_URL is not an https URL");
    }

    @Test
    void doesNotWarnForHttpsOrWhenTheModeIsOff(CapturedOutput output) {
        new MultiTenantConfig(props("https://docs0.example/", true)).announce();
        new MultiTenantConfig(props("http://localhost:8080", false)).announce();

        assertThat(output.getAll()).doesNotContain("not an https URL");
    }

    @Test
    void thePublicBaseUrlHasNoTrailingSlash() {
        assertThat(props("https://docs0.example///", true).publicBaseUrl()).isEqualTo("https://docs0.example");
        assertThat(props("HTTPS://docs0.example", true).publicUrlIsHttps()).isTrue();
        assertThat(props("http://docs0.example", true).publicUrlIsHttps()).isFalse();
    }
}

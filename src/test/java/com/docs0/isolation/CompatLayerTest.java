package com.docs0.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.tenant.TenantService;
import com.docs0.tenant.local.LocalAdminAuthenticator;
import com.docs0.tenant.local.LocalTenantResolver;
import com.docs0.web.admin.AdminPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The only place the single configured tenant may be assumed is the compatibility layer
 * ({@code com.docs0.tenant.local}) and the two entry points that serve the pilot by design. Everything else is told
 * which tenant it works for.
 */
class CompatLayerTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String LOCAL_PACKAGE = "com/docs0/tenant/local/";
    /** Entry points that serve the configured local tenant by design. */
    private static final List<String> PILOT_ENTRY_POINTS = List.of(
            "com/docs0/slack/SlackGateway.java",     // Socket Mode serves the one configured Slack app
            "com/docs0/web/StatusController.java");  // the public status page and /health report the local tenant

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String relative(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    @Test
    void noCodeAssumesAGlobalCurrentTenant() throws IOException {
        for (Path file : mainSources()) {
            String text = Files.readString(file);
            assertThat(text).as(relative(file)).doesNotContainPattern("\\.current\\(\\)")
                    .doesNotContainPattern("Tenant\\s+current\\(");
        }
        assertThat(TenantService.class.getDeclaredMethods()).extracting(m -> m.getName()).doesNotContain("current",
                "reset");
    }

    @Test
    void theLocalTenantCompatLayerIsUsedOnlyByThePilotEntryPoints() throws IOException {
        for (Path file : mainSources()) {
            String name = relative(file);
            if (name.startsWith(LOCAL_PACKAGE) || PILOT_ENTRY_POINTS.contains(name)) {
                continue;
            }
            assertThat(Files.readString(file)).as(name).doesNotContain("com.docs0.tenant.local")
                    .doesNotContain("LocalTenantResolver");
        }
    }

    @Test
    void theConfiguredSlackTokenIsReadOnlyByThePilot() throws IOException {
        for (Path file : mainSources()) {
            String name = relative(file);
            if (name.startsWith(LOCAL_PACKAGE) || PILOT_ENTRY_POINTS.contains(name) || name.equals(
                    "com/docs0/config/Docs0Properties.java")) {
                continue;
            }
            assertThat(Files.readString(file)).as(name).doesNotContain("botToken()");
        }
    }

    @Test
    void everyTenantScopedControllerGetsItsTenantFromThePrincipal() throws IOException {
        List<String> controllers;
        try (Stream<Path> files = Files.walk(MAIN.resolve("com/docs0/web/admin"))) {
            controllers = files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .map(p -> p.getFileName().toString()).collect(Collectors.toList());
        }
        assertThat(controllers).isNotEmpty();
        for (String controller : controllers) {
            String text = Files.readString(MAIN.resolve("com/docs0/web/admin/" + controller));
            assertThat(text).as(controller).doesNotContain("TenantService.current").contains("AdminPrincipal admin");
        }
    }

    // ---- the single-tenant sign-in ---------------------------------------------------------------------

    private static Docs0Properties props(String password) {
        return new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null, null,
                new Docs0Properties.Slack("", ""), null, null, "", "http://localhost:8080", password);
    }

    private static LocalAdminAuthenticator authenticator(String password) {
        TestDatabase.resetWithTenant();
        Docs0Properties props = props(password);
        return new LocalAdminAuthenticator(props,
                new LocalTenantResolver(new TenantService(TestDatabase.jdbc()), props));
    }

    private static MockHttpServletRequest from(String address, String basic) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/sources");
        request.setRemoteAddr(address);
        if (basic != null) {
            request.addHeader("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }

    @Test
    void thePasswordSignsInAsAdministratorOfTheConfiguredTenant() throws IOException {
        LocalAdminAuthenticator authenticator = authenticator("s3cret");
        long configured = TestDatabase.jdbc().sql("SELECT id FROM tenant WHERE slug = 'smalltech'")
                .query(Long.class).single();

        Optional<AdminPrincipal> ok = authenticator.authenticate(from("203.0.113.5", "admin:s3cret"));
        assertThat(ok).get().extracting(AdminPrincipal::tenantId).isEqualTo(configured);
        assertThat(authenticator.authenticate(from("203.0.113.5", "admin:wrong"))).isEmpty();
        assertThat(authenticator.authenticate(from("203.0.113.5", "root:s3cret"))).isEmpty();
        assertThat(authenticator.authenticate(from("127.0.0.1", null))).isEmpty();

        MockHttpServletResponse denied = new MockHttpServletResponse();
        authenticator.deny(from("203.0.113.5", null), denied);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getHeader("WWW-Authenticate")).contains("Basic");
    }

    @Test
    void withoutAPasswordOnlyTheLocalMachineSignsInAsTheConfiguredTenant() throws IOException {
        LocalAdminAuthenticator authenticator = authenticator("");

        assertThat(authenticator.authenticate(from("127.0.0.1", null))).isPresent();
        assertThat(authenticator.authenticate(from("203.0.113.5", null))).isEmpty();
        MockHttpServletRequest proxied = from("127.0.0.1", null);
        proxied.addHeader("X-Forwarded-For", "203.0.113.5");
        assertThat(authenticator.authenticate(proxied)).isEmpty();

        MockHttpServletResponse denied = new MockHttpServletResponse();
        authenticator.deny(from("203.0.113.5", null), denied);
        assertThat(denied.getStatus()).isEqualTo(403);
    }
}

package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** With DOCS0_ADMIN_PASSWORD set, /admin needs HTTP Basic as user "admin", from anywhere. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "docs0.admin-password=correct horse: battery")
class AdminPasswordTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse: battery";

    @Autowired
    MockMvc mvc;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder remote(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        });
    }

    @Test
    void withoutCredentialsTheBrowserIsChallengedEvenOnTheLocalMachine() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                get("/admin/sources"), remote(get("/admin/sources")), get("/admin/sources/google/callback"),
                post("/admin/sources/disconnect"), get("/admin/nothing-here")}) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Basic realm=\"docs0 admin\", charset=\"UTF-8\""));
        }
    }

    @Test
    void theRightPasswordIsAcceptedFromAnywhere() throws Exception {
        String html = mvc.perform(remote(get("/admin/sources")).header("Authorization", basic("admin", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<h1>Sources</h1>");

        mvc.perform(get("/admin/sources").header("Authorization", "basic " + basic("admin", PASSWORD).substring(6)))
                .andExpect(status().isOk());
        // Through a proxy is fine once a password protects the pages.
        mvc.perform(get("/admin/sources").header("X-Forwarded-For", "203.0.113.9")
                        .header("Authorization", basic("admin", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        String[] wrong = {
                basic("admin", "wrong"),
                basic("admin", ""),
                basic("admin", PASSWORD + "x"),
                basic("admin", PASSWORD.substring(1)),
                basic("root", PASSWORD),
                basic("", PASSWORD),
                basic("Admin", PASSWORD),
                "Basic " + Base64.getEncoder().encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)),
                "Basic not-base64!!",
                "Basic ",
                "Bearer " + PASSWORD,
                PASSWORD,
        };
        for (String authorization : wrong) {
            mvc.perform(get("/admin/sources").header("Authorization", authorization))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"));
        }
    }

    @Test
    void aPasswordDoesNotReplaceTheCsrfToken() throws Exception {
        mvc.perform(post("/admin/sources/disconnect").header("Authorization", basic("admin", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStatusPageNeedsNoPassword() throws Exception {
        mvc.perform(remote(get("/"))).andExpect(status().isOk());
        mvc.perform(remote(get("/health"))).andExpect(status().isOk());
    }
}

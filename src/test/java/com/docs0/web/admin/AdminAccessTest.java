package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The plain test profile: no admin password, no Google OAuth client, no encryption key.
 * So /admin is for this machine only and the Sources page explains the setup.
 */
@AutoConfigureMockMvc
class AdminAccessTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder from(String remoteAddress, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(remoteAddress);
            return r;
        });
    }

    @Test
    void withoutAPasswordTheLocalMachineIsAllowed() throws Exception {
        for (String local : new String[] {"127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "127.0.0.2"}) {
            mvc.perform(from(local, get("/admin/sources"))).andExpect(status().isOk());
        }
    }

    @Test
    void withoutAPasswordEveryOtherAddressGets403() throws Exception {
        for (String remote : new String[] {"203.0.113.9", "192.168.1.20", "10.0.0.5", "2001:db8::1", "not-an-ip-x"}) {
            for (String path : new String[] {"/admin/sources", "/admin/sources/google/connect",
                    "/admin/sources/google/callback", "/admin", "/admin/", "/admin/anything/else"}) {
                mvc.perform(from(remote, get(path))).andExpect(status().isForbidden());
            }
            mvc.perform(from(remote, post("/admin/sources/disconnect"))).andExpect(status().isForbidden());
        }
        // Even with the right password header, since no password is configured.
        mvc.perform(from("203.0.113.9", get("/admin/sources")).header("Authorization", "Basic YWRtaW46"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLocalRequestThatCameThroughAProxyIsNotTreatedAsLocal() throws Exception {
        mvc.perform(get("/admin/sources").header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/sources").header("Forwarded", "for=203.0.113.9"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStatusPageAndHealthStayOpenAndTheStatusPageLinksToSources() throws Exception {
        String status = mvc.perform(from("203.0.113.9", get("/"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(status).contains("<a href=\"/admin/sources\">Sources</a>");
        assertThat(status).contains("not connected — connect it on the Sources page");

        String health = mvc.perform(from("203.0.113.9", get("/health"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(health).contains("\"status\":\"UP\"").contains("\"Google Drive\":{")
                .contains("\"state\":\"OFF\"");
    }

    @Test
    void setupIncompleteNamesEveryMissingSettingAndTheRedirectUriAndOffersNoConnectButton() throws Exception {
        String html = mvc.perform(get("/admin/sources")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"setup-incomplete\"")
                .contains("<code>DOCS0_GOOGLE_OAUTH_CLIENT_ID</code>")
                .contains("<code>DOCS0_GOOGLE_OAUTH_CLIENT_SECRET</code>")
                .contains("<code>DOCS0_ENCRYPTION_KEY</code>")
                .contains("openssl rand -base64 32")
                .contains("<code>http://localhost:8080/admin/sources/google/callback</code>");
        assertThat(html).doesNotContain("Connect Google Drive").doesNotContain("/admin/sources/google/connect")
                .doesNotContain("id=\"not-connected\"").doesNotContain("id=\"connected\"");
    }

    @Test
    void connectingIsNotPossibleUntilSetupIsComplete() throws Exception {
        mvc.perform(get("/admin/sources/google/connect")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/sources"));
    }
}

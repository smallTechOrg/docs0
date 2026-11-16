package com.docs0.web.admin;

import com.docs0.tenant.local.LocalAdminAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Lets a test sign in to the admin pages as the administrator of any tenant by sending the header
 * {@code X-Test-Tenant: <tenant id>}. Without the header the configured sign-in applies as usual.
 */
@TestConfiguration
public class TwoTenantAdminConfig {

    public static final String HEADER = "X-Test-Tenant";

    @Bean
    @Primary
    AdminAuthenticator headerAuthenticator(LocalAdminAuthenticator local) {
        return new AdminAuthenticator() {
            @Override
            public Optional<AdminPrincipal> authenticate(HttpServletRequest request) {
                String tenant = request.getHeader(HEADER);
                if (tenant != null) {
                    return Optional.of(new AdminPrincipal(Long.parseLong(tenant), "test-admin-" + tenant));
                }
                return local.authenticate(request);
            }

            @Override
            public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
                local.deny(request, response);
            }
        };
    }
}

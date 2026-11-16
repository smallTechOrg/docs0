package com.docs0.tenant.local;

import com.docs0.config.Docs0Properties;
import com.docs0.web.admin.AdminAuthenticator;
import com.docs0.web.admin.AdminGuard;
import com.docs0.web.admin.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The pilot sign-in: with DOCS0_ADMIN_PASSWORD set, anyone who signs in over HTTP Basic as user "admin"; without
 * it, only requests made directly from this machine. Either way the principal administers the configured tenant.
 */
@Component
public class LocalAdminAuthenticator implements AdminAuthenticator {

    private static final String USER = "admin";

    private final Docs0Properties props;
    private final LocalTenantResolver local;

    public LocalAdminAuthenticator(Docs0Properties props, LocalTenantResolver local) {
        this.props = props;
        this.local = local;
    }

    @Override
    public Optional<AdminPrincipal> authenticate(HttpServletRequest request) {
        boolean allowed = props.adminPasswordSet()
                ? passwordMatches(request.getHeader("Authorization"))
                : fromThisMachine(request);
        if (!allowed) {
            return Optional.empty();
        }
        var tenant = local.tenant();
        return Optional.of(new AdminPrincipal(tenant.id(), USER));
    }

    @Override
    public void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (props.adminPasswordSet()) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"docs0 admin\", charset=\"UTF-8\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Admin pages are only available on the machine docs0 runs on until DOCS0_ADMIN_PASSWORD is set.");
        }
    }

    private boolean passwordMatches(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        // Both comparisons always run, so a wrong user name takes as long as a wrong password.
        boolean userOk = AdminGuard.sameValue(decoded.substring(0, colon), USER);
        boolean passwordOk = AdminGuard.sameValue(decoded.substring(colon + 1), props.adminPassword());
        return userOk & passwordOk;
    }

    /**
     * True for a loopback connection that did not come through a proxy. A reverse proxy on
     * this machine makes every visitor look local, so anything carrying forwarding headers
     * is refused: set DOCS0_ADMIN_PASSWORD when docs0 sits behind a proxy.
     */
    private static boolean fromThisMachine(HttpServletRequest request) {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null) {
            return false;
        }
        try {
            // The remote address is an IP literal, so this does no DNS lookup.
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}

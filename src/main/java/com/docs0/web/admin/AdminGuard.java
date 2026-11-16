package com.docs0.web.admin;

import com.docs0.config.Docs0Properties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards every request under /admin.
 *
 * <p>Who may enter: with DOCS0_ADMIN_PASSWORD set, anyone who signs in over HTTP Basic as
 * user "admin". Without it, only requests made directly from this machine.
 *
 * <p>Requests that change something (anything but GET, HEAD, OPTIONS) must also carry the
 * session's CSRF token in the {@code _csrf} form field.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    public static final String CSRF_PARAMETER = "_csrf";

    private static final String CSRF_SESSION_ATTRIBUTE = "docs0.csrf";
    private static final String USER = "admin";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Docs0Properties props;

    public AdminGuard(Docs0Properties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (props.adminPasswordSet()) {
            if (!passwordMatches(request.getHeader("Authorization"))) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"docs0 admin\", charset=\"UTF-8\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        } else if (!fromThisMachine(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Admin pages are only available on the machine docs0 runs on until DOCS0_ADMIN_PASSWORD is set.");
            return false;
        }
        if (!READ_METHODS.contains(request.getMethod()) && !csrfTokenMatches(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This form has expired. Go back, reload and retry.");
            return false;
        }
        return true;
    }

    /** The session's CSRF token, created on first use. Put it in a hidden {@code _csrf} field of every form. */
    public static String csrfToken(HttpSession session) {
        synchronized (session) {
            Object existing = session.getAttribute(CSRF_SESSION_ATTRIBUTE);
            if (existing instanceof String token) {
                return token;
            }
            String token = randomToken();
            session.setAttribute(CSRF_SESSION_ATTRIBUTE, token);
            return token;
        }
    }

    /** An unguessable URL-safe value (256 random bits). */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Compares without revealing, through timing, where two values differ. */
    public static boolean sameValue(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
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
        boolean userOk = sameValue(decoded.substring(0, colon), USER);
        boolean passwordOk = sameValue(decoded.substring(colon + 1), props.adminPassword());
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

    private static boolean csrfTokenMatches(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(CSRF_SESSION_ATTRIBUTE);
        return expected instanceof String token && sameValue(token, request.getParameter(CSRF_PARAMETER));
    }
}

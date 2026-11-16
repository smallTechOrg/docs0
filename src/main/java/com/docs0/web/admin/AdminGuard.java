package com.docs0.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards every request under /admin.
 *
 * <p>Who may enter, and which tenant they administer, is decided by the {@link AdminAuthenticator}; the result is
 * an {@link AdminPrincipal} that controllers receive as a method parameter. The pilot authenticator lets in anyone
 * who signs in over HTTP Basic as user "admin" when DOCS0_ADMIN_PASSWORD is set, and otherwise only requests made
 * directly from this machine.
 *
 * <p>Requests that change something (anything but GET, HEAD, OPTIONS) must also carry the
 * session's CSRF token in the {@code _csrf} form field.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    public static final String CSRF_PARAMETER = "_csrf";

    private static final String CSRF_SESSION_ATTRIBUTE = "docs0.csrf";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The request attribute that carries the signed-in {@link AdminPrincipal}. */
    public static final String PRINCIPAL_ATTRIBUTE = "docs0.adminPrincipal";

    private final AdminAuthenticator authenticator;

    public AdminGuard(AdminAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Optional<AdminPrincipal> principal = authenticator.authenticate(request);
        if (principal.isEmpty()) {
            authenticator.deny(request, response);
            return false;
        }
        if (!READ_METHODS.contains(request.getMethod()) && !csrfTokenMatches(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This form has expired. Go back, reload and retry.");
            return false;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
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

    private static boolean csrfTokenMatches(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(CSRF_SESSION_ATTRIBUTE);
        return expected instanceof String token && sameValue(token, request.getParameter(CSRF_PARAMETER));
    }
}

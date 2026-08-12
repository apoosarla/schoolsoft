package com.schoolsoft.platform.security;

import com.schoolsoft.platform.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant for every request by parsing the bearer JWT and
 * populating {@link TenantContext}. Runs before controllers; Spring Security
 * then has an authentication principal.
 *
 * Public endpoints (auth, public site, webhooks) bypass this resolver — they
 * are matched by path prefix.
 */
@Component
public class TenantResolverFilter extends OncePerRequestFilter {

    private static final List<String> PUBLIC_PREFIXES = List.of(
        "/v1/auth/", "/v1/public/", "/actuator/health", "/actuator/info", "/v1/webhooks/"
    );

    private final JwtService jwt;

    public TenantResolverFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        for (String prefix : PUBLIC_PREFIXES) {
            if (p.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            unauthorized(res, "missing_token", "Missing bearer token");
            return;
        }
        try {
            Claims c = jwt.parse(auth.substring(7));
            if (!"access".equals(c.get("typ", String.class))) {
                unauthorized(res, "wrong_token_type", "Wrong token type");
                return;
            }
            String cid = c.get("cid", String.class);
            String cs  = c.get("cs",  String.class);
            String sidStr = c.get("sid", String.class);
            String st  = c.get("st", String.class);
            UUID userId = UUID.fromString(c.getSubject());
            UUID schoolId = (sidStr == null || sidStr.isEmpty()) ? null : UUID.fromString(sidStr);
            UUID chainId  = "platform".equals(cid) ? null : UUID.fromString(cid);

            TenantContext.set(new TenantContext.Snapshot(cs, chainId, schoolId, userId, st, false));

            var authToken = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + st.toUpperCase()))
            );
            SecurityContextHolder.getContext().setAuthentication(authToken);

            chain.doFilter(req, res);
        } catch (JwtException ex) {
            // Expiry is the common case and the one every client keys its refresh on.
            String code = (ex instanceof ExpiredJwtException) ? "token_expired" : "bad_token";
            unauthorized(res, code, "Bad token: " + ex.getMessage());
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Writes the 401 body directly rather than calling
     * {@code HttpServletResponse#sendError}: sendError re-dispatches through
     * {@code /error}, which the security chain then rejects as an anonymous
     * request and rewrites to 403 — turning "your token expired, refresh it"
     * into "you may not do this", which no client can act on.
     */
    private void unauthorized(HttpServletResponse res, String code, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}

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

    /**
     * The only paths a chain admin may reach.
     *
     * <p>A chain admin has no school — {@code sid} is absent from their token
     * by construction — and V009's policies read
     * {@code school_id = current_school_id() OR current_school_id() IS NULL}.
     * A null school is therefore not "no schools" but <em>every</em> school in
     * the chain, which is exactly what makes their one console possible: they
     * list the schools in their chain because RLS steps aside, not because a
     * handler loops.</p>
     *
     * <p>That is safe while the only thing such a session can ask for is a
     * school. It stops being safe the moment the same session can reach a
     * read built for one school, because that read returns every school's rows
     * merged together, silently, with no error and no 403 — the chain admin
     * holds every unrestricted read in {@code PermissionChecker}'s baseline,
     * so permissions do not stop it. Before this list the shape of the apps
     * was the only thing preventing it, and the apps have just been merged.</p>
     *
     * <p>So the gate is here rather than in a screen: a bookmark, a stale tab
     * or a curl with a chain admin's token gets a 403 instead of another
     * school's ledger. Widening this list is a deliberate edit, and anything
     * added to it has to be a read that names its own school or is genuinely
     * chain-wide.</p>
     */
    private static final List<String> CHAIN_ADMIN_PREFIXES = List.of(
        "/v1/tenancy/schools", "/v1/iam/me"
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

            if ("chain_admin".equals(st) && !isChainAdminPath(req.getRequestURI())) {
                forbidden(res, "chain_admin_scope",
                    "A chain HQ admin may read the schools in their chain and open one. "
                    + "Working inside a school is the school's own staff's.");
                return;
            }

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

    private static boolean isChainAdminPath(String path) {
        for (String prefix : CHAIN_ADMIN_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
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

    /** Same reasoning as {@link #unauthorized}: written directly, not via sendError. */
    private void forbidden(HttpServletResponse res, String code, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}

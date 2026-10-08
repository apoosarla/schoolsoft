package com.schoolsoft.audit.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.audit.api.OperatorTrail;
import com.schoolsoft.platform.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

/**
 * Writes the operators' trail: one row for every request made as a
 * {@code platform_admin}, whatever it was and however it ended (SEC-06).
 *
 * <p>Keyed on who is asking, not on an annotation or a path. {@link
 * AuditInterceptor} audits the endpoints somebody remembered to mark, which is
 * right for a school's high-risk mutations and wrong here: an operator reaches
 * across every chain, their reads are as much the customer's business as their
 * writes, and an endpoint added next year must not be the one that was
 * forgotten.</p>
 */
@Component
public class OperatorAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuditInterceptor.class);

    private static final String ATTR_ACTOR = "schoolsoft.operator.actor";

    private final OperatorTrail trail;
    private final ObjectMapper json = new ObjectMapper();

    public OperatorAuditInterceptor(OperatorTrail trail) { this.trail = trail; }

    /**
     * Takes the operator's identity now. A handler that steps into a chain
     * replaces the tenant context and clears it on the way out, so by {@link
     * #afterCompletion} there may be nobody left on the thread to name.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        var snap = TenantContext.get();
        if (snap != null && "platform_admin".equals(snap.subjectType()) && snap.userAccountId() != null) {
            request.setAttribute(ATTR_ACTOR, snap.userAccountId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UUID actor = (UUID) request.getAttribute(ATTR_ACTOR);
        if (actor == null) return;

        try {
            String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            // An exception nothing translated has not reached the response yet.
            int status = ex != null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            trail.record(actor,
                request.getMethod() + " " + (route == null ? request.getRequestURI() : route),
                path, OperatorTrail.chainOf(request), readBody(request), status,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        } catch (Exception e) {
            log.error("Operator audit entry for {} {} could not be written",
                request.getMethod(), request.getRequestURI(), e);
        }
    }

    private JsonNode readBody(HttpServletRequest request) {
        var cached = WebUtils.getNativeRequest(request, CachedBodyFilter.CachedBodyRequest.class);
        if (cached == null || cached.body().length == 0) return null;
        try {
            return json.readTree(new String(cached.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}

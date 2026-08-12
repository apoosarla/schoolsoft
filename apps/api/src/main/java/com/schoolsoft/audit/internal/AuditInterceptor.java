package com.schoolsoft.audit.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.audit.api.Audited;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

/**
 * Writes the audit entry for any endpoint carrying {@link Audited} (GAP-27).
 *
 * One interceptor rather than an audit call inside each of the fifteen
 * mutations that need one: the coverage is then visible at the endpoint, and a
 * new high-risk endpoint is audited by annotating it rather than by remembering
 * a convention.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    private static final String ATTR_ANNOTATION = "schoolsoft.audit.annotation";
    private static final String ATTR_TARGET_ID = "schoolsoft.audit.targetId";
    private static final String ATTR_BEFORE = "schoolsoft.audit.before";
    private static final String ATTR_BODY = "schoolsoft.audit.body";

    private final AuditService audit;
    private final AuditSnapshots snapshots;
    private final ObjectMapper json = new ObjectMapper();

    public AuditInterceptor(AuditService audit, AuditSnapshots snapshots) {
        this.audit = audit;
        this.snapshots = snapshots;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;
        Audited audited = method.getMethodAnnotation(Audited.class);
        if (audited == null) return true;

        JsonNode body = readBody(request);
        String reason = text(body, "reason");

        if (audited.requireReason() && (reason == null || reason.isBlank())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(Map.of(
                "code", "reason_required",
                "message", "This action changes a record other people rely on. "
                    + "Send a 'reason' describing why.")));
            return false;
        }

        UUID targetId = resolveTargetId(request, body, audited.idParam());
        request.setAttribute(ATTR_ANNOTATION, audited);
        request.setAttribute(ATTR_TARGET_ID, targetId);
        request.setAttribute(ATTR_BODY, body);
        if (audited.snapshot()) {
            request.setAttribute(ATTR_BEFORE, snapshots.of(audited.targetType(), targetId));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Audited audited = (Audited) request.getAttribute(ATTR_ANNOTATION);
        if (audited == null) return;
        // A refused mutation changed nothing; the attempt is the security log's
        // business, not the audit trail's.
        if (ex != null || response.getStatus() >= 400) return;

        try {
            UUID targetId = (UUID) request.getAttribute(ATTR_TARGET_ID);
            JsonNode body = (JsonNode) request.getAttribute(ATTR_BODY);
            JsonNode before = (JsonNode) request.getAttribute(ATTR_BEFORE);
            JsonNode after = audited.snapshot()
                ? snapshots.of(audited.targetType(), targetId)
                : body;
            audit.record(audited.action(), audited.targetType(), targetId,
                before, after, text(body, "reason"), body);
        } catch (Exception e) {
            log.error("Audit entry for {} could not be written", audited.action(), e);
        }
    }

    /**
     * The id from the URI template first — {@code /enrolment/{id}/status} names
     * its target in the path — then the body, which is where a grant or a
     * concession names the person it is about.
     */
    private UUID resolveTargetId(HttpServletRequest request, JsonNode body, String idParam) {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVars =
            (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = pathVars == null ? null : pathVars.get(idParam);
        if (raw == null) raw = text(body, idParam);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
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

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

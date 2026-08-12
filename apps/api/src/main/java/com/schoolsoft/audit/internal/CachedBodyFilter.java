package com.schoolsoft.audit.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the JSON body of a mutating request readable after the controller has
 * consumed it, so {@link AuditInterceptor} can take the reason out of it
 * without every audited endpoint having to hand the reason over itself.
 *
 * Only JSON writes are wrapped: an upload has no reason in it and no business
 * being buffered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CachedBodyFilter extends OncePerRequestFilter {

    /** Bodies beyond this are not audit payloads; they stream through untouched. */
    private static final int MAX_CACHED_BYTES = 256 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method)
            || "PATCH".equals(method) || "DELETE".equals(method);
        String contentType = request.getContentType();
        boolean json = contentType != null
            && contentType.toLowerCase().startsWith("application/json");
        return !mutating || !json || request.getContentLength() > MAX_CACHED_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new CachedBodyRequest(request), response);
    }

    /** A request whose body can be read more than once. */
    public static final class CachedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = StreamUtils.copyToByteArray(request.getInputStream());
        }

        public byte[] body() { return body; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

package com.schoolsoft.audit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as a high-risk mutation: the interceptor records
 * who did it, what the row looked like before and after, and why (SEC-08).
 *
 * The annotation exists so that adding a mutation to the audited set is one
 * line at the endpoint rather than a hand-written {@code AuditService.record}
 * call buried in a repository — the calls drift, and the ones that drift are
 * the ones nobody notices are missing.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Dotted action name, e.g. {@code enrolment.status_change}. */
    String action();

    /**
     * The audited entity. When {@link #snapshot()} is true this is also the
     * table the before/after states are read from, so it must name a real
     * table.
     */
    String targetType();

    /**
     * Where the target id comes from: a URI template variable of this name,
     * falling back to a top-level field of the JSON request body.
     */
    String idParam() default "id";

    /**
     * True for mutations of an existing row — the interceptor reads the row
     * before and after the call. False for creations, where there is no
     * "before" and the request payload is the record of what was asked for.
     */
    boolean snapshot() default true;

    /**
     * Whether a non-blank {@code reason} in the request body is mandatory.
     * A waiver or an unlock without a reason is an unanswerable question three
     * months later, so these endpoints refuse it up front.
     */
    boolean requireReason() default true;
}

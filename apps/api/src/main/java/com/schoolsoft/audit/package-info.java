/**
 * Audit module — append-only log of every state-changing action. Mandatory for
 * DPDP and useful for support / forensics. Other modules call
 * {@link com.schoolsoft.audit.api.AuditService#record} when they mutate domain state.
 *
 * <p>Two logs, because there are two places to stand. A chain's {@code audit_log}
 * records what its own people did and is the customer's to read. Schoolsoft's
 * operators stand in no chain, so what they do — across every chain, reads
 * included — goes to {@code platform.operator_audit_log} through
 * {@link com.schoolsoft.audit.api.OperatorTrail}.</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package com.schoolsoft.audit;

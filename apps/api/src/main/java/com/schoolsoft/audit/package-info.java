/**
 * Audit module — append-only log of every state-changing action. Mandatory for
 * DPDP and useful for support / forensics. Other modules call
 * {@link com.schoolsoft.audit.api.AuditService#record} when they mutate domain state.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package com.schoolsoft.audit;

/**
 * Schoolsoft platform — modular monolith root.
 *
 * Each direct subpackage is a Spring Modulith module:
 *  - platform   : cross-cutting shared (tenant resolution, db config, security primitives, web)
 *  - iam        : identity & access — OTP, JWT, RBAC scopes
 *  - tenancy    : chain/school/campus/AY/term/grade/section
 *  - people     : student/guardian/staff + relationships
 *  - notification : push/email/whatsapp routing + template lifecycle
 *  - file       : object store, signed URLs
 *  - audit      : append-only audit log
 *  - featureflags : per-tenant toggles
 *  - theming    : per-school white-label
 *  - search     : OpenSearch index aliases
 *  - eventbus   : outbox → in-process bus (carve to Kafka later)
 *  - jobs       : scheduled + retryable background work
 *  - curriculum : polymorphic curriculum engine + per-board strategies
 *
 * Cross-module access goes through each module's `api` subpackage. The `internal`
 * subpackage is module-private (enforced by Spring Modulith verification tests).
 */
package com.schoolsoft;

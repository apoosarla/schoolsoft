/**
 * IAM module — identity, OTP login, JWT issue/refresh, RBAC scopes.
 *
 * Public API (com.schoolsoft.iam.api) exposes the login flows and an authorization
 * helper other modules may use to check scoped permissions. Internal package
 * holds the OTP store, password/secret material, and repositories.
 */
@org.springframework.modulith.ApplicationModule(displayName = "IAM")
package com.schoolsoft.iam;

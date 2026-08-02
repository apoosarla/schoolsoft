/**
 * Search — wraps OpenSearch with per-tenant index aliases. MVP keeps it as a
 * minimal interface so the rest of the app can be wired against it; the
 * OpenSearch client gets wired in once the indexing flow needs to ship.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Search")
package com.schoolsoft.search;

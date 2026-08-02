/**
 * File module — object-store metadata, signed-URL issuance, virus-scan hooks,
 * image variants, retention policy. The bytes themselves live in S3/MinIO;
 * this module owns the lifecycle records.
 */
@org.springframework.modulith.ApplicationModule(displayName = "File")
package com.schoolsoft.file;

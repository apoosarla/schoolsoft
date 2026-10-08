package com.schoolsoft.privacy.api;

import java.time.Instant;
import java.util.UUID;

/** One answer a family gave. {@code standing} is false once it was withdrawn. */
public record ConsentDto(
    UUID id, UUID studentId, String purpose, boolean standing,
    Instant grantedAt, Instant revokedAt, String source
) {}

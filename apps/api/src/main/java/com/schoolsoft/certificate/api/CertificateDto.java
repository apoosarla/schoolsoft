package com.schoolsoft.certificate.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * An issued certificate.
 *
 * <p>{@code payload} is the document: every field that was printed, as it stood
 * on {@code issuedOn}. It is a map rather than a record because a Transfer
 * Certificate, a transcript and a bonafide certificate carry genuinely different
 * fields, and a union record would be three quarters null on every row — which
 * is also how a renderer ends up printing "Total working days: null".</p>
 *
 * <p>{@code payloadHash} is over that map. {@code /verify} recomputes it, so a
 * family or a receiving school can be told the row has not been touched since
 * the day the school signed it.</p>
 */
public record CertificateDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID enrolmentId,
    UUID withdrawalId,
    String kind,
    String serialNo,
    LocalDate issuedOn,
    UUID issuedByStaffId,
    Map<String, Object> payload,
    String payloadHash,
    Instant revokedAt,
    String revokedReason,
    UUID supersededById
) {
    public boolean live() { return revokedAt == null; }
}

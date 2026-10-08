package com.schoolsoft.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record OperatorAuditEntryDto(
    long id, UUID actorUserId, String actorEmail, String action, String path, UUID chainId,
    JsonNode requestPayload, int status, Instant occurredAt
) {}

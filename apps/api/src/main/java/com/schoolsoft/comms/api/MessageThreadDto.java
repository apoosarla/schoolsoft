package com.schoolsoft.comms.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageThreadDto(
    UUID id, UUID schoolId, UUID subjectStudentId, List<UUID> participants, Instant lastMessageAt
) {}

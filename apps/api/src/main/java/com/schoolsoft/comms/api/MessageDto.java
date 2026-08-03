package com.schoolsoft.comms.api;

import java.time.Instant;
import java.util.UUID;

public record MessageDto(UUID id, UUID threadId, UUID senderUserId, String body, Instant sentAt) {}

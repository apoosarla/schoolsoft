package com.schoolsoft.people.api;

import java.util.UUID;

public record GuardianDto(
    UUID id,
    UUID schoolId,
    String firstName,
    String lastName,
    String phone,
    String email,
    boolean optInWhatsapp,
    boolean optInPush,
    boolean optInEmail
) {}

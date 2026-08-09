package com.schoolsoft.people.api;

import java.util.UUID;

public record UserDirectoryEntryDto(
    UUID userAccountId,
    String subjectType,
    UUID subjectId,
    String displayName,
    String email,
    String phone
) {}

package com.schoolsoft.iam.api;

import java.util.List;
import java.util.UUID;

public record StaffWithRolesDto(
    UUID staffId,
    UUID userAccountId,
    String firstName,
    String lastName,
    String email,
    List<String> roleCodes
) {}

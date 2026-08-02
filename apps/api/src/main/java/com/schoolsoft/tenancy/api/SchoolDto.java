package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record SchoolDto(
    UUID id,
    String slug,
    String name,
    String boardCode,
    String gstin,
    String stateCode,
    boolean isActive
) {}

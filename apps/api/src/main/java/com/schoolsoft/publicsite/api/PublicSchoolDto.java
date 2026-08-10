package com.schoolsoft.publicsite.api;

import java.util.UUID;

/** Deliberately slimmer than {@code tenancy.api.SchoolDto} — no GSTIN/state code on a public endpoint. */
public record PublicSchoolDto(UUID id, String slug, String name, String boardCode) {}

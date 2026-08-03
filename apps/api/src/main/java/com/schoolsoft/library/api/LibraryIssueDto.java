package com.schoolsoft.library.api;

import java.time.LocalDate;
import java.util.UUID;

public record LibraryIssueDto(
    UUID id, UUID copyId, String memberType, UUID memberId,
    LocalDate issuedOn, LocalDate dueOn, LocalDate returnedOn, double fine, boolean finePaid
) {}

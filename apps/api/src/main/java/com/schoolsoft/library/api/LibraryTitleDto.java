package com.schoolsoft.library.api;

import java.util.List;
import java.util.UUID;

public record LibraryTitleDto(
    UUID id, UUID schoolId, String isbn, String title, String author, String publisher, Integer year, List<String> subjectTags
) {}

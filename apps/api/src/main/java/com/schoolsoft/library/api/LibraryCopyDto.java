package com.schoolsoft.library.api;

import java.util.UUID;

public record LibraryCopyDto(UUID id, UUID titleId, String barcode, String status) {}

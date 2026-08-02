package com.schoolsoft.eventbus.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain event payloads emitted by the Attendance module. Consumers
 * (Notification, Analytics) subscribe via Spring listeners.
 */
public final class AttendanceEvents {
    private AttendanceEvents() {}

    public record StudentMarkedAbsent(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String source
    ) {}

    public record StudentMarkedPresent(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String source
    ) {}
}

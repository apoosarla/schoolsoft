package com.schoolsoft.attendance.api;

import java.util.List;
import java.util.UUID;

/**
 * What became of a register sent from a device that may have been out of
 * touch (ATT-09): the marks that landed, and the ones that did not because the
 * record had moved since the sender last read it.
 *
 * <p>A conflict is not an error and the request does not fail for one. The
 * rest of the register is saved, and each conflict comes back with both
 * values so a person can choose between them.</p>
 */
public record AttendanceSyncResultDto(List<AttendanceRecordDto> applied, List<Conflict> conflicts) {

    /**
     * @param kind   {@code changed} — somebody recorded something else in the
     *               meantime; resend with {@code theirs.markedAt} to overrule
     *               it. {@code refused} — the mark cannot be written at all
     *               (the register was signed off, the day is a holiday), and
     *               {@code message} says why.
     * @param theirs the record as it stands, absent when there is none
     */
    public record Conflict(UUID studentId, String kind, String yours, AttendanceRecordDto theirs, String message) {}
}

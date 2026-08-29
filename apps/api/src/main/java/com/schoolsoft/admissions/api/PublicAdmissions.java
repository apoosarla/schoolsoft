package com.schoolsoft.admissions.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The two things the public site may do to the admissions funnel: lodge an
 * application, and let the family that lodged it check on it.
 *
 * <p>Exists so that {@code publicsite} — which serves unauthenticated
 * traffic — talks to a named, minimal surface instead of holding a reference
 * to {@code AdmissionsRepository} and being able to reach {@code transition},
 * {@code recordTestScore} and {@code convertToStudent} from an endpoint with
 * no token behind it.</p>
 */
public interface PublicAdmissions {

    /**
     * Both fields must match, so a guessed application number alone is not
     * enough to read another family's record.
     */
    Optional<AdmissionApplicationDto> findByApplicationNoAndPhone(String applicationNo, String guardianPhone);

    /** Lodges an application. {@code source} records where it came from. */
    AdmissionApplicationDto create(
        UUID schoolId, UUID academicYearId, UUID gradeId, String applicationNo,
        String firstName, String lastName, LocalDate dob, String gender,
        String guardianName, String guardianPhone, String guardianEmail, String source);
}

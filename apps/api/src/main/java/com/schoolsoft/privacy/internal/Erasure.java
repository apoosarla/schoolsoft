package com.schoolsoft.privacy.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Removes who a child and their family were, and leaves the rows the school
 * is obliged to keep.
 *
 * <p>Nothing is deleted. An invoice, an attendance mark and a certificate all
 * point at the student's id; deleting the row would either take them with it
 * or leave them pointing nowhere, and a school must be able to produce its
 * ledger and its register for years after the child has gone. So the row
 * stays and the person leaves it.</p>
 *
 * <p>A certificate is not touched. It is frozen at issue and hashed, and it is
 * the school's statement of record — the one document about this child a
 * later school or a board may still ask the school to stand behind.</p>
 */
@Repository
public class Erasure {

    private static final String STUDENT =
        "UPDATE student SET first_name = 'Erased', middle_name = NULL, last_name = NULL, dob = NULL, "
            + "gender = NULL, blood_group = NULL, apaar_id = NULL, photo_file_id = NULL, "
            + "encrypted_payload = NULL, erased_at = now(), updated_at = now() WHERE id = ?";

    // A parent with another child still on the books is still the school's
    // parent; only one who was here for this child alone goes with them.
    private static final String GUARDIANS_OF_THIS_CHILD_ALONE =
        "SELECT gs.guardian_id FROM guardian_student gs WHERE gs.student_id = ? AND NOT EXISTS ("
            + "SELECT 1 FROM guardian_student other JOIN student s ON s.id = other.student_id "
            + "WHERE other.guardian_id = gs.guardian_id AND other.student_id <> gs.student_id "
            + "AND s.erased_at IS NULL)";

    private static final String GUARDIAN =
        "UPDATE guardian SET first_name = 'Erased', last_name = NULL, phone = NULL, email = NULL, "
            + "occupation = NULL, encrypted_payload = NULL, opt_in_whatsapp = FALSE, opt_in_email = FALSE, "
            + "opt_in_push = FALSE, opt_in_sms = FALSE, opt_in_source = NULL, opt_in_at = NULL, "
            + "erased_at = now() WHERE id = ?";

    // A login needs an address or a number (V002's CHECK), and both are unique
    // across the chain. The placeholder is the account's own id, so it frees
    // the real address for whoever holds it next and can collide with nothing.
    private static final String LOGIN =
        "UPDATE user_account SET is_active = FALSE, phone = NULL, "
            + "email = 'erased-' || id || '@erased.invalid' "
            + "WHERE subject_type = ? AND subject_id = ?";

    // The link is what made the child theirs to see. A parent who stays, for a
    // sibling, stops being attached to a child the school no longer knows.
    private static final String LINKS = "DELETE FROM guardian_student WHERE student_id = ?";

    // The form the family first filled in holds the same names a second time.
    private static final String APPLICATION =
        "UPDATE admission_application SET applicant_first_name = 'Erased', applicant_last_name = NULL, "
            + "applicant_dob = NULL, guardian_name = 'Erased', guardian_phone = 'erased', "
            + "guardian_email = NULL WHERE converted_student_id = ?";

    private final JdbcTemplate jdbc;

    public Erasure(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void erase(UUID studentId) {
        List<UUID> guardians = jdbc.query(GUARDIANS_OF_THIS_CHILD_ALONE,
            (rs, i) -> UUID.fromString(rs.getString(1)), studentId);
        for (UUID guardianId : guardians) {
            jdbc.update(GUARDIAN, guardianId);
            jdbc.update(LOGIN, "guardian", guardianId);
        }
        jdbc.update(LINKS, studentId);
        jdbc.update(LOGIN, "student", studentId);
        jdbc.update(APPLICATION, studentId);
        jdbc.update(STUDENT, studentId);
    }
}

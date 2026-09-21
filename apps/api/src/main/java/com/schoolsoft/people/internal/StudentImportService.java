package com.schoolsoft.people.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.enrolment.api.RollNumbers;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.people.api.ImportBatchDto;
import com.schoolsoft.people.api.ImportResultDto;
import com.schoolsoft.people.api.ImportRowDto;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import com.schoolsoft.tenancy.api.SectionCapacity;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk import of students, their families and their enrolments (GAP-23,
 * ENR-09). Schools arrive with a spreadsheet; this is the door for it.
 *
 * <h2>Preview, then commit</h2>
 * A preview parses the file, resolves every grade and section, checks every
 * row and writes nothing. The commit runs the stored batch rather than the
 * file, so what the office approved on screen is exactly what lands, and a
 * second upload cannot slip a different file under an approval.
 *
 * <h2>All of it or none of it</h2>
 * ENR-09 leaves the choice open and asks it to be documented, so: a batch
 * with one bad row commits nothing. A register assembled out of the rows that
 * happened to parse is worse than no register — nobody can tell afterwards
 * which children are missing from it, and the file is the only place anybody
 * would look. The preview is where a file gets fixed, and it names every row
 * that needs fixing at once rather than one per attempt.
 *
 * <h2>Families</h2>
 * Siblings share parents, so a guardian is matched on phone (then email)
 * within the school before a new one is written — both against the rows
 * already in this batch and against the guardians the school already has.
 * That is what keeps a file of 500 children from inventing 500 families, and
 * it is the same match {@code AdmissionsRepository} makes when it converts a
 * single application.
 */
@Service
public class StudentImportService {

    /** Genders and relations the schema will accept; anything else is a row error rather than a 500. */
    private static final Set<String> GENDERS = Set.of("male", "female", "other", "undisclosed");
    private static final Set<String> RELATIONS = Set.of("father", "mother", "guardian", "grandparent", "other");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NumberSeries numbers;
    private final RollNumbers rollNumbers;
    private final SectionCapacity capacity;
    private final Authz authz;

    public StudentImportService(JdbcTemplate jdbc, ObjectMapper json, NumberSeries numbers,
                                RollNumbers rollNumbers, SectionCapacity capacity, Authz authz) {
        this.jdbc = jdbc;
        this.json = json;
        this.numbers = numbers;
        this.rollNumbers = rollNumbers;
        this.capacity = capacity;
        this.authz = authz;
    }

    // ------------------------------------------------------------- preview

    @Transactional
    public ImportBatchDto preview(UUID schoolId, String filename, String csv) {
        List<Map<String, String>> parsed = Csv.parse(csv);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                "That file has a header row and nothing under it.");
        }

        Map<String, Section> sections = sectionsOf(schoolId);
        Set<String> takenAdmissionNos = new HashSet<>(jdbc.queryForList(
            "SELECT admission_no FROM student WHERE school_id = ?", String.class, schoolId));
        Set<String> seenAdmissionNos = new HashSet<>();
        Map<UUID, Integer> wantedPerSection = new HashMap<>();

        List<ImportRowDto> rows = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            // +2: the header is line 1, and a spreadsheet counts from 1.
            rows.add(check(parsed.get(i), i + 2, sections, takenAdmissionNos, seenAdmissionNos, wantedPerSection));
        }
        // Capacity is a property of the file as a whole, not of any one row:
        // forty children into a section with three seats left is a fact about
        // the thirty-eighth onwards, and saying so per row would be a lie
        // about which child is the problem.
        flagOverfilledSections(rows, sections, wantedPerSection);

        int errorCount = (int) rows.stream().filter(r -> !r.ok()).count();
        UUID id = UUID.randomUUID();
        // A school that previews the same file twice keeps one live batch;
        // the older one is not deleted, because "what did we approve on
        // Tuesday" is a real question.
        jdbc.update("UPDATE import_batch SET status = 'superseded' " +
            "WHERE school_id = ? AND status = 'previewed' AND filename IS NOT DISTINCT FROM ?",
            schoolId, filename);
        jdbc.update(
            "INSERT INTO import_batch (id, school_id, kind, filename, status, row_count, error_count, " +
            "  rows, uploaded_by_staff_id) VALUES (?, ?, 'student', ?, 'previewed', ?, ?, ?::jsonb, ?)",
            id, schoolId, filename, rows.size(), errorCount, write(rows), authz.currentStaffId());
        return find(id);
    }

    // -------------------------------------------------------------- commit

    /**
     * Writes the batch. One transaction: a child whose family wrote but whose
     * enrolment did not is the "partial guardian orphan" ENR-09 names, and the
     * only way to be sure of not leaving one is to leave nothing.
     *
     * <p>Committing a batch that is already committed is a conflict rather
     * than a second import — the click that landed and the click that repeated
     * it look identical from here, and one of them has to lose.</p>
     */
    @Transactional
    public ImportResultDto commit(UUID batchId) {
        ImportBatchDto batch = find(batchId);
        if (!"previewed".equals(batch.status())) {
            throw new ConflictException("This import was already " + batch.status()
                + ". Preview the file again to import it.");
        }
        if (batch.errorCount() > 0) {
            throw new ConflictException("This file still has " + batch.errorCount()
                + (batch.errorCount() == 1 ? " row" : " rows") + " to fix. Nothing was imported.");
        }

        Map<String, UUID> guardiansInThisBatch = new HashMap<>();
        int studentsCreated = 0;
        int guardiansCreated = 0;
        int guardiansReused = 0;
        int enrolled = 0;

        for (ImportRowDto row : batch.rows()) {
            UUID studentId = insertStudent(batch.schoolId(), row);
            studentsCreated++;

            String override = capacity.reserveSeat(row.sectionId(), null);
            jdbc.update(
                "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, " +
                "  starts_on, status, roll_no, over_capacity_reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)",
                UUID.randomUUID(), batch.schoolId(), studentId, row.sectionId(), row.academicYearId(),
                Date.valueOf(LocalDate.now()),
                rollNumbers.nextFor(batch.schoolId(), row.sectionId(), row.rollNo()), override);
            enrolled++;

            String key = guardianKey(row);
            if (key != null) {
                UUID guardianId = guardiansInThisBatch.get(key);
                if (guardianId != null) {
                    guardiansReused++;
                } else {
                    UUID existing = existingGuardian(batch.schoolId(), row);
                    if (existing != null) {
                        guardianId = existing;
                        guardiansReused++;
                    } else {
                        guardianId = insertGuardian(batch.schoolId(), row);
                        guardiansCreated++;
                    }
                    guardiansInThisBatch.put(key, guardianId);
                }
                boolean firstChild = jdbc.queryForObject(
                    "SELECT count(*) FROM guardian_student WHERE guardian_id = ?",
                    Integer.class, guardianId) == 0;
                jdbc.update(
                    "INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) " +
                    "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    guardianId, studentId,
                    row.guardianRelation() == null || row.guardianRelation().isBlank()
                        ? "guardian" : row.guardianRelation().toLowerCase(Locale.ROOT),
                    firstChild);
            }
        }

        jdbc.update("UPDATE import_batch SET status = 'committed', committed_at = now() " +
            "WHERE id = ? AND status = 'previewed'", batchId);
        return new ImportResultDto(batchId, studentsCreated, guardiansCreated, guardiansReused, enrolled);
    }

    // --------------------------------------------------------------- reads

    public ImportBatchDto find(UUID id) {
        var rows = jdbc.query(
            "SELECT id, school_id, filename, status, row_count, error_count, rows, created_at, committed_at " +
            "FROM import_batch WHERE id = ?",
            (rs, i) -> new ImportBatchDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("filename"),
                rs.getString("status"),
                rs.getInt("row_count"),
                rs.getInt("error_count"),
                rs.getInt("error_count") == 0 && "previewed".equals(rs.getString("status")),
                read(rs.getString("rows")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant()),
            id);
        if (rows.isEmpty()) throw new NotFoundException("No import batch " + id);
        return rows.get(0);
    }

    public List<ImportBatchDto> recent(UUID schoolId) {
        // The list is the history, and history does not need every row of
        // every file in it.
        return jdbc.query(
            "SELECT id, school_id, filename, status, row_count, error_count, '[]'::jsonb AS rows, " +
            "  created_at, committed_at FROM import_batch WHERE school_id = ? " +
            "ORDER BY created_at DESC LIMIT 25",
            (rs, i) -> new ImportBatchDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("filename"),
                rs.getString("status"),
                rs.getInt("row_count"),
                rs.getInt("error_count"),
                rs.getInt("error_count") == 0 && "previewed".equals(rs.getString("status")),
                List.of(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant()),
            schoolId);
    }

    // ---------------------------------------------------------- validation

    private record Section(UUID id, UUID academicYearId, String gradeCode, String code) {}

    /** Every section of the school's current year, keyed by the pair a spreadsheet names it with. */
    private Map<String, Section> sectionsOf(UUID schoolId) {
        Map<String, Section> byCode = new HashMap<>();
        jdbc.query(
            "SELECT s.id, s.academic_year_id, g.code AS grade_code, s.code " +
            "FROM section s JOIN grade g ON g.id = s.grade_id " +
            "JOIN academic_year ay ON ay.id = s.academic_year_id AND ay.is_current " +
            "WHERE s.school_id = ?",
            rs -> {
                Section section = new Section(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("academic_year_id")),
                    rs.getString("grade_code"),
                    rs.getString("code"));
                byCode.put(key(section.gradeCode(), section.code()), section);
            },
            schoolId);
        return byCode;
    }

    private static String key(String gradeCode, String sectionCode) {
        return (gradeCode + "\u0000" + sectionCode).toLowerCase(Locale.ROOT);
    }

    private ImportRowDto check(Map<String, String> raw, int line, Map<String, Section> sections,
                               Set<String> takenAdmissionNos, Set<String> seenAdmissionNos,
                               Map<UUID, Integer> wantedPerSection) {
        List<String> errors = new ArrayList<>();
        String admissionNo = raw.get("admission_no");
        String firstName = raw.get("first_name");
        String dob = raw.get("dob");
        String gender = lower(raw.get("gender"));
        String gradeCode = raw.get("grade_code");
        String sectionCode = raw.get("section_code");
        String guardianName = raw.get("guardian_name");
        String guardianRelation = lower(raw.get("guardian_relation"));
        String guardianPhone = raw.get("guardian_phone");
        String guardianEmail = raw.get("guardian_email");

        if (blank(firstName)) errors.add("first_name is required");

        Section section = null;
        if (blank(gradeCode) || blank(sectionCode)) {
            errors.add("grade_code and section_code are required — a child has to land in a section");
        } else {
            section = sections.get(key(gradeCode, sectionCode));
            if (section == null) {
                errors.add("no section '" + sectionCode + "' in grade '" + gradeCode
                    + "' for the current academic year");
            } else {
                wantedPerSection.merge(section.id(), 1, Integer::sum);
            }
        }

        if (!blank(admissionNo)) {
            if (!takenAdmissionNos.add(admissionNo)) {
                errors.add("admission number '" + admissionNo + "' already belongs to a student here");
            } else if (!seenAdmissionNos.add(admissionNo)) {
                errors.add("admission number '" + admissionNo + "' appears twice in this file");
            }
        }

        if (!blank(dob)) {
            try {
                LocalDate.parse(dob.trim());
            } catch (DateTimeParseException e) {
                errors.add("dob '" + dob + "' is not a date — write it as YYYY-MM-DD");
            }
        }
        if (!blank(gender) && !GENDERS.contains(gender)) {
            errors.add("gender '" + gender + "' is not one of " + GENDERS);
        }
        if (!blank(guardianRelation) && !RELATIONS.contains(guardianRelation)) {
            errors.add("guardian_relation '" + guardianRelation + "' is not one of " + RELATIONS);
        }
        // A guardian nobody can be reached on is a row that looks complete and
        // is not: the family gets no login, no fee notice and no message.
        boolean anyGuardianField = !blank(guardianName) || !blank(guardianPhone) || !blank(guardianEmail);
        if (anyGuardianField) {
            if (blank(guardianName)) errors.add("guardian_name is required when a guardian is given");
            if (blank(guardianPhone) && blank(guardianEmail)) {
                errors.add("a guardian needs a phone or an email — it is how the school reaches them, "
                    + "and how their other children are matched to them");
            }
        }

        return new ImportRowDto(line, trim(admissionNo), trim(firstName), trim(raw.get("middle_name")),
            trim(raw.get("last_name")), trim(dob), gender, trim(gradeCode), trim(sectionCode),
            trim(raw.get("roll_no")),
            section == null ? null : section.id(), section == null ? null : section.academicYearId(),
            trim(guardianName), guardianRelation, trim(guardianPhone), trim(guardianEmail), errors);
    }

    private void flagOverfilledSections(List<ImportRowDto> rows, Map<String, Section> sections,
                                        Map<UUID, Integer> wantedPerSection) {
        Set<UUID> overfilled = new HashSet<>();
        for (var wanted : wantedPerSection.entrySet()) {
            var occupancy = capacity.occupancyOf(wanted.getKey());
            if (occupancy.seatsLeft() != null && wanted.getValue() > occupancy.seatsLeft()) {
                overfilled.add(wanted.getKey());
            }
        }
        if (overfilled.isEmpty()) return;
        for (int i = 0; i < rows.size(); i++) {
            ImportRowDto row = rows.get(i);
            if (row.sectionId() == null || !overfilled.contains(row.sectionId())) continue;
            var occupancy = capacity.occupancyOf(row.sectionId());
            List<String> errors = new ArrayList<>(row.errors());
            errors.add("this file puts " + wantedPerSection.get(row.sectionId()) + " children into "
                + row.gradeCode() + "-" + row.sectionCode() + ", which has "
                + occupancy.seatsLeft() + " seats left");
            rows.set(i, withErrors(row, errors));
        }
    }

    // ------------------------------------------------------------- writing

    private UUID insertStudent(UUID schoolId, ImportRowDto row) {
        UUID id = UUID.randomUUID();
        String admissionNo = blank(row.admissionNo())
            ? numbers.next(schoolId, NumberSeries.Kind.admission, null, "ADM{YY}{SEQ:4}", null)
            : row.admissionNo();
        jdbc.update(
            "INSERT INTO student (id, school_id, admission_no, first_name, middle_name, last_name, dob, gender) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, admissionNo, row.firstName(), row.middleName(), row.lastName(),
            blank(row.dob()) ? null : Date.valueOf(LocalDate.parse(row.dob())),
            blank(row.gender()) ? null : row.gender());
        return id;
    }

    private UUID existingGuardian(UUID schoolId, ImportRowDto row) {
        var byPhone = blank(row.guardianPhone()) ? List.<UUID>of() : jdbc.query(
            "SELECT id FROM guardian WHERE school_id = ? AND phone = ? LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId, row.guardianPhone());
        if (!byPhone.isEmpty()) return byPhone.get(0);
        var byEmail = blank(row.guardianEmail()) ? List.<UUID>of() : jdbc.query(
            "SELECT id FROM guardian WHERE school_id = ? AND email = ? LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId, row.guardianEmail());
        return byEmail.isEmpty() ? null : byEmail.get(0);
    }

    private UUID insertGuardian(UUID schoolId, ImportRowDto row) {
        UUID id = UUID.randomUUID();
        String name = row.guardianName().trim();
        String first = name.split("\\s+")[0];
        String last = name.contains(" ") ? name.substring(name.indexOf(' ') + 1) : null;
        jdbc.update(
            "INSERT INTO guardian (id, school_id, first_name, last_name, phone, email) VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, first, last, emptyToNull(row.guardianPhone()), emptyToNull(row.guardianEmail()));
        // The login that lets the family in. ON CONFLICT because `user_account`
        // is unique on phone and on email across the whole chain: a parent who
        // is also a member of staff, or who already has a child at a sister
        // school, keeps the account they have rather than failing the import.
        jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, phone, email) " +
            "VALUES (?, ?, 'guardian', ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), schoolId, id, emptyToNull(row.guardianPhone()), emptyToNull(row.guardianEmail()));
        return id;
    }

    /** What two rows have to agree on to be the same family. */
    private static String guardianKey(ImportRowDto row) {
        if (!blank(row.guardianPhone())) return "p:" + row.guardianPhone().trim();
        if (!blank(row.guardianEmail())) return "e:" + row.guardianEmail().trim().toLowerCase(Locale.ROOT);
        return null;
    }

    // ------------------------------------------------------------- plumbing

    private static ImportRowDto withErrors(ImportRowDto row, List<String> errors) {
        return new ImportRowDto(row.line(), row.admissionNo(), row.firstName(), row.middleName(),
            row.lastName(), row.dob(), row.gender(), row.gradeCode(), row.sectionCode(), row.rollNo(),
            row.sectionId(), row.academicYearId(), row.guardianName(), row.guardianRelation(),
            row.guardianPhone(), row.guardianEmail(), errors);
    }

    private String write(List<ImportRowDto> rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the parsed batch", e);
        }
    }

    private List<ImportRowDto> read(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<ImportRowDto>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the stored batch", e);
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static String emptyToNull(String s) { return blank(s) ? null : s.trim(); }

    private static String lower(String s) { return s == null ? null : s.trim().toLowerCase(Locale.ROOT); }
}

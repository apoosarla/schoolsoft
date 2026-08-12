package com.schoolsoft.certification.support;

import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.tenancy.api.ChainProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The certification seed fixture: one chain, two schools (CBSE + Cambridge),
 * each carrying a full prior academic year of history — enrolments, timetable,
 * attendance, marks, report cards, invoices and payments — plus a current
 * academic year in progress.
 *
 * Every scenario in {@code docs/certification-test-scenarios.md} runs against
 * this fixture. It is rebuilt from scratch on the first test of a JVM run
 * (schema dropped, chain re-provisioned, rows re-inserted) so a run never
 * depends on what a previous run left behind. Ids are derived deterministically
 * from natural keys, which keeps a failure reproducible across runs.
 *
 * "Today" for the fixture is {@link #TODAY}: the current AY (2026-27) is four
 * months in, so scenarios have both settled history and live in-flight data.
 */
public class CertificationFixture {

    private static final Logger log = LoggerFactory.getLogger(CertificationFixture.class);

    public static final String CHAIN_SLUG = "certchain";
    public static final String CHAIN_SCHEMA = "chain_" + CHAIN_SLUG;
    public static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    private static final LocalDate PRIOR_AY_START = LocalDate.of(2025, 4, 1);
    private static final LocalDate PRIOR_AY_END   = LocalDate.of(2026, 3, 31);
    private static final LocalDate CURRENT_AY_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate CURRENT_AY_END   = LocalDate.of(2027, 3, 31);

    /** Month of attendance/marks history seeded per AY, per focus section. */
    private static final LocalDate PRIOR_HISTORY_MONTH_START = LocalDate.of(2025, 7, 1);
    private static final LocalDate PRIOR_HISTORY_MONTH_END   = LocalDate.of(2025, 7, 31);
    private static final LocalDate CURRENT_HISTORY_MONTH_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate CURRENT_HISTORY_MONTH_END   = LocalDate.of(2026, 7, 31);

    private static final List<String> CBSE_GRADES =
        List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    private static final List<String> CIE_GRADES = List.of("Y9", "Y10", "Y11", "Y12", "Y13");
    private static final List<String> CBSE_SECTIONS = List.of("A", "B");
    private static final List<String> CIE_SECTIONS = List.of("A");

    private static final Map<String, String> CBSE_SUBJECTS = new LinkedHashMap<>(Map.of(
        "ENG", "English", "HIN", "Hindi", "MATH", "Mathematics",
        "SCI", "Science", "SST", "Social Science", "CS", "Computer Science"
    ));
    private static final Map<String, String> CIE_SUBJECTS = new LinkedHashMap<>(Map.of(
        "0500", "English First Language", "0580", "Mathematics",
        "0620", "Chemistry", "0625", "Physics", "0455", "Economics"
    ));

    private static final Map<String, String> PHONE_BANDS = Map.of("oakridge", "98", "riverdale", "97");

    /** younger grade index → older sibling's grade index (owners are never sharers). */
    private static final Map<Integer, Integer> SIBLING_GRADE_PAIRS = Map.of(2, 0, 4, 1, 6, 3);

    private static final int CBSE_STUDENTS_PER_SECTION = 10;
    private static final int CIE_STUDENTS_PER_SECTION = 8;
    private static final int TEACHERS_PER_SCHOOL = 8;
    private static final int SECTION_CAPACITY = 30;
    private static final int BULK_SECTION_SIZE = 40;

    private final DataSource dataSource;
    private final JdbcTemplate platformJdbc;
    private final ChainProvisioningService provisioning;

    public CertificationFixture(DataSource dataSource, JdbcTemplate platformJdbc,
                                ChainProvisioningService provisioning) {
        this.dataSource = dataSource;
        this.platformJdbc = platformJdbc;
        this.provisioning = provisioning;
    }

    // ------------------------------------------------------------------ types

    public record AySeed(UUID id, String code, LocalDate startsOn, LocalDate endsOn) {}

    public record SchoolSeed(
        UUID id, String slug, String name, String boardCode, String strategyCode,
        UUID mainCampusId, UUID annexCampusId,
        AySeed priorAy, AySeed currentAy,
        UUID curriculumId,
        UUID principalStaffId, UUID principalUserId,
        UUID accountantStaffId, UUID accountantUserId,
        UUID registrarStaffId, UUID registrarUserId,
        UUID librarianStaffId, UUID librarianUserId,
        List<UUID> teacherStaffIds, List<UUID> teacherUserIds,
        List<String> gradeCodes, List<String> sectionCodes, List<String> subjectCodes,
        String focusGradeCode, String terminalGradeCode
    ) {
        /** The section every "one section, one month of history" scenario uses. */
        public String focusSectionCode() { return "A"; }
    }

    public record Seed(UUID chainId, String chainSchema, String chainSlug,
                       SchoolSeed cbse, SchoolSeed cie, UUID chainAdminUserId) {}

    // ------------------------------------------------------------------- seed

    /** Drops and rebuilds the whole fixture. Returns the ids scenarios need. */
    public Seed rebuild() {
        long started = System.currentTimeMillis();
        dropExistingChain();
        var chain = provisioning.provision(CHAIN_SLUG, "Certification Chain", "enterprise");

        Seed seed = inChain(chain.id(), jdbc -> {
            SchoolSeed cbse = seedSchool(jdbc, "oakridge", "Oakridge Public School",
                "CBSE", "CBSE-CCE-2024", CBSE_GRADES, CBSE_SECTIONS, CBSE_SUBJECTS,
                CBSE_STUDENTS_PER_SECTION, "5", "12");
            SchoolSeed cie = seedSchool(jdbc, "riverdale", "Riverdale International School",
                "CIE", "CIE-IGCSE", CIE_GRADES, CIE_SECTIONS, CIE_SUBJECTS,
                CIE_STUDENTS_PER_SECTION, "Y10", "Y13");
            UUID chainAdminUser = seedChainAdmin(jdbc);
            seedBulkStudents(jdbc, cbse);
            return new Seed(chain.id(), CHAIN_SCHEMA, CHAIN_SLUG, cbse, cie, chainAdminUser);
        });

        log.info("Certification fixture rebuilt in {} ms", System.currentTimeMillis() - started);
        return seed;
    }

    private void dropExistingChain() {
        TenantContext.set(TenantContext.platformAdmin(null));
        try {
            platformJdbc.execute("DROP SCHEMA IF EXISTS " + CHAIN_SCHEMA + " CASCADE");
            platformJdbc.update(
                "DELETE FROM platform.chain_schema_version WHERE chain_id IN " +
                "(SELECT id FROM platform.chain WHERE slug = ?)", CHAIN_SLUG);
            platformJdbc.update("DELETE FROM platform.chain WHERE slug = ?", CHAIN_SLUG);
        } finally {
            TenantContext.clear();
        }
    }

    // ----------------------------------------------------------------- school

    private SchoolSeed seedSchool(JdbcTemplate jdbc, String slug, String name,
                                  String boardCode, String strategyCode,
                                  List<String> gradeCodes, List<String> sectionCodes,
                                  Map<String, String> subjects, int studentsPerSection,
                                  String focusGradeCode, String terminalGradeCode) {
        UUID schoolId = id(slug + ":school");
        jdbc.update(
            "INSERT INTO school (id, slug, name, board_code, gstin, state_code, address) " +
            "VALUES (?, ?, ?, ?, ?, ?, '{\"city\":\"Hyderabad\",\"state\":\"Telangana\"}'::jsonb)",
            schoolId, slug, name, boardCode, "36AABCU9603R1ZM", "36");

        UUID mainCampus = id(slug + ":campus:main");
        UUID annexCampus = id(slug + ":campus:annex");
        jdbc.update("INSERT INTO campus (id, school_id, name, is_primary) VALUES (?, ?, ?, TRUE)",
            mainCampus, schoolId, "Main Campus");
        jdbc.update("INSERT INTO campus (id, school_id, name, is_primary) VALUES (?, ?, ?, FALSE)",
            annexCampus, schoolId, "Annex Campus");

        AySeed priorAy = seedAcademicYear(jdbc, schoolId, slug, "2025-26", PRIOR_AY_START, PRIOR_AY_END, false);
        AySeed currentAy = seedAcademicYear(jdbc, schoolId, slug, "2026-27", CURRENT_AY_START, CURRENT_AY_END, true);

        UUID curriculumId = id(slug + ":curriculum");
        jdbc.update(
            "INSERT INTO curriculum (id, school_id, board_code, strategy_code, name, version, is_published) " +
            "VALUES (?, ?, ?, ?, ?, ?, TRUE)",
            curriculumId, schoolId, boardCode, strategyCode, name + " Core Curriculum", "2026.1");

        Map<String, UUID> grades = seedGrades(jdbc, schoolId, slug, gradeCodes);
        Map<String, UUID> subjectIds = seedSubjects(jdbc, schoolId, slug, subjects);
        seedSections(jdbc, schoolId, slug, grades, sectionCodes, priorAy, strategyCode, curriculumId);
        seedSections(jdbc, schoolId, slug, grades, sectionCodes, currentAy, strategyCode, curriculumId);

        StaffSeed staff = seedStaff(jdbc, schoolId, slug);
        seedSectionSubjectTeachers(jdbc, schoolId, slug, gradeCodes, sectionCodes,
            subjectIds, staff.teacherStaffIds, currentAy);

        seedStudents(jdbc, schoolId, slug, gradeCodes, sectionCodes, studentsPerSection,
            priorAy, currentAy);

        String focusSection = "A";
        UUID focusSectionCurrent = sectionId(slug, currentAy.code(), focusGradeCode, focusSection);
        UUID focusSectionPrior = sectionId(slug, priorAy.code(), focusGradeCode, focusSection);

        seedTimetable(jdbc, slug, focusSectionCurrent, subjectIds, staff.teacherStaffIds, CURRENT_AY_START);
        seedTimetable(jdbc, slug, focusSectionPrior, subjectIds, staff.teacherStaffIds, PRIOR_AY_START);

        seedAttendance(jdbc, schoolId, focusSectionPrior, staff.teacherStaffIds.get(0),
            PRIOR_HISTORY_MONTH_START, PRIOR_HISTORY_MONTH_END);
        seedAttendance(jdbc, schoolId, focusSectionCurrent, staff.teacherStaffIds.get(0),
            CURRENT_HISTORY_MONTH_START, CURRENT_HISTORY_MONTH_END);

        seedAssessmentsAndMarks(jdbc, schoolId, slug, focusSectionPrior, priorAy, subjectIds,
            staff.teacherStaffIds.get(0), strategyCode, "published");
        seedAssessmentsAndMarks(jdbc, schoolId, slug, focusSectionCurrent, currentAy, subjectIds,
            staff.teacherStaffIds.get(0), strategyCode, "marking");

        seedReportCards(jdbc, schoolId, slug, focusSectionPrior, priorAy, strategyCode);
        seedFees(jdbc, schoolId, slug, grades, priorAy, currentAy);
        seedLibrary(jdbc, schoolId, slug, focusSectionCurrent);
        seedTransport(jdbc, schoolId, slug, focusSectionCurrent);

        return new SchoolSeed(schoolId, slug, name, boardCode, strategyCode,
            mainCampus, annexCampus, priorAy, currentAy, curriculumId,
            staff.principalStaffId, staff.principalUserId,
            staff.accountantStaffId, staff.accountantUserId,
            staff.registrarStaffId, staff.registrarUserId,
            staff.librarianStaffId, staff.librarianUserId,
            staff.teacherStaffIds, staff.teacherUserIds,
            gradeCodes, sectionCodes, List.copyOf(subjects.keySet()),
            focusGradeCode, terminalGradeCode);
    }

    private AySeed seedAcademicYear(JdbcTemplate jdbc, UUID schoolId, String slug, String code,
                                    LocalDate startsOn, LocalDate endsOn, boolean isCurrent) {
        UUID ayId = id(slug + ":ay:" + code);
        jdbc.update(
            "INSERT INTO academic_year (id, school_id, code, starts_on, ends_on, is_current) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            ayId, schoolId, code, startsOn, endsOn, isCurrent);
        jdbc.update(
            "INSERT INTO term (id, academic_year_id, code, name, starts_on, ends_on) VALUES (?, ?, 'T1', 'Term 1', ?, ?)",
            id(slug + ":term:" + code + ":T1"), ayId, startsOn, startsOn.plusMonths(6).minusDays(1));
        jdbc.update(
            "INSERT INTO term (id, academic_year_id, code, name, starts_on, ends_on) VALUES (?, ?, 'T2', 'Term 2', ?, ?)",
            id(slug + ":term:" + code + ":T2"), ayId, startsOn.plusMonths(6), endsOn);
        return new AySeed(ayId, code, startsOn, endsOn);
    }

    private Map<String, UUID> seedGrades(JdbcTemplate jdbc, UUID schoolId, String slug, List<String> gradeCodes) {
        Map<String, UUID> grades = new LinkedHashMap<>();
        for (int i = 0; i < gradeCodes.size(); i++) {
            String code = gradeCodes.get(i);
            UUID gradeId = id(slug + ":grade:" + code);
            grades.put(code, gradeId);
            jdbc.update("INSERT INTO grade (id, school_id, code, name, sort_order) VALUES (?, ?, ?, ?, ?)",
                gradeId, schoolId, code, "Grade " + code, i + 1);
        }
        return grades;
    }

    private Map<String, UUID> seedSubjects(JdbcTemplate jdbc, UUID schoolId, String slug, Map<String, String> subjects) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        subjects.forEach((code, name) -> {
            UUID subjectId = id(slug + ":subject:" + code);
            ids.put(code, subjectId);
            jdbc.update("INSERT INTO subject (id, school_id, code, name) VALUES (?, ?, ?, ?)",
                subjectId, schoolId, code, name);
        });
        return ids;
    }

    private void seedSections(JdbcTemplate jdbc, UUID schoolId, String slug, Map<String, UUID> grades,
                              List<String> sectionCodes, AySeed ay, String strategyCode, UUID curriculumId) {
        List<Object[]> rows = new ArrayList<>();
        grades.forEach((gradeCode, gradeId) -> {
            for (String sectionCode : sectionCodes) {
                rows.add(new Object[]{
                    sectionId(slug, ay.code(), gradeCode, sectionCode), schoolId, gradeId, ay.id(),
                    sectionCode, "Grade " + gradeCode + "-" + sectionCode,
                    curriculumId, strategyCode, SECTION_CAPACITY
                });
            }
        });
        jdbc.batchUpdate(
            "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, curriculum_id, strategy_code, capacity) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    // ------------------------------------------------------------------ staff

    private record StaffSeed(UUID principalStaffId, UUID principalUserId,
                             UUID accountantStaffId, UUID accountantUserId,
                             UUID registrarStaffId, UUID registrarUserId,
                             UUID librarianStaffId, UUID librarianUserId,
                             List<UUID> teacherStaffIds, List<UUID> teacherUserIds) {}

    private StaffSeed seedStaff(JdbcTemplate jdbc, UUID schoolId, String slug) {
        UUID principal = staffMember(jdbc, schoolId, slug, "principal", "Meera", "Rao", "principal");
        UUID accountant = staffMember(jdbc, schoolId, slug, "accountant", "Suresh", "Iyer", "accountant");
        UUID registrar = staffMember(jdbc, schoolId, slug, "registrar", "Fatima", "Sheikh", "registrar");
        UUID librarian = staffMember(jdbc, schoolId, slug, "librarian", "Anil", "Kumar", "librarian");

        List<UUID> teacherStaffIds = new ArrayList<>();
        List<UUID> teacherUserIds = new ArrayList<>();
        for (int i = 1; i <= TEACHERS_PER_SCHOOL; i++) {
            String key = "teacher" + i;
            UUID staffId = staffMember(jdbc, schoolId, slug, key, "Teacher", String.valueOf(i), "subject_teacher");
            teacherStaffIds.add(staffId);
            teacherUserIds.add(id(slug + ":user:" + key));
        }
        // Teacher 1 is the class teacher of every focus section; teacher 2 covers grade B sections.
        jdbc.update("INSERT INTO staff_role (id, staff_id, role_code, scope_type, scope_id) VALUES (?, ?, 'class_teacher', 'school', ?)",
            id(slug + ":role:teacher1:class_teacher"), teacherStaffIds.get(0), schoolId);

        return new StaffSeed(principal, id(slug + ":user:principal"),
            accountant, id(slug + ":user:accountant"),
            registrar, id(slug + ":user:registrar"),
            librarian, id(slug + ":user:librarian"),
            teacherStaffIds, teacherUserIds);
    }

    private UUID staffMember(JdbcTemplate jdbc, UUID schoolId, String slug, String key,
                             String firstName, String lastName, String roleCode) {
        UUID staffId = id(slug + ":staff:" + key);
        UUID userId = id(slug + ":user:" + key);
        String email = key + "@" + slug + ".test";
        jdbc.update(
            "INSERT INTO staff (id, school_id, employee_no, first_name, last_name, email, employment_type, joined_on) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'permanent', ?)",
            staffId, schoolId, "EMP-" + key.toUpperCase(), firstName, lastName, email, PRIOR_AY_START.minusYears(2));
        jdbc.update(
            "INSERT INTO staff_role (id, staff_id, role_code, scope_type, scope_id) VALUES (?, ?, ?, 'school', ?)",
            id(slug + ":role:" + key + ":" + roleCode), staffId, roleCode, schoolId);
        jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) VALUES (?, ?, 'staff', ?, ?)",
            userId, schoolId, staffId, email);
        return staffId;
    }

    private UUID seedChainAdmin(JdbcTemplate jdbc) {
        UUID userId = id("chain:user:admin");
        jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) VALUES (?, NULL, 'chain_admin', NULL, ?)",
            userId, "hq@certchain.test");
        return userId;
    }

    private void seedSectionSubjectTeachers(JdbcTemplate jdbc, UUID schoolId, String slug,
                                            List<String> gradeCodes, List<String> sectionCodes,
                                            Map<String, UUID> subjectIds, List<UUID> teacherStaffIds,
                                            AySeed ay) {
        List<Object[]> rows = new ArrayList<>();
        int teacherCursor = 0;
        for (String gradeCode : gradeCodes) {
            for (String sectionCode : sectionCodes) {
                UUID section = sectionId(slug, ay.code(), gradeCode, sectionCode);
                boolean primaryAssigned = false;
                for (Map.Entry<String, UUID> subject : subjectIds.entrySet()) {
                    UUID teacher = teacherStaffIds.get(teacherCursor % teacherStaffIds.size());
                    teacherCursor++;
                    rows.add(new Object[]{
                        id(slug + ":sst:" + ay.code() + ":" + gradeCode + ":" + sectionCode + ":" + subject.getKey()),
                        section, subject.getValue(), teacher, !primaryAssigned
                    });
                    primaryAssigned = true;
                }
            }
        }
        jdbc.batchUpdate(
            "INSERT INTO section_subject_teacher (id, section_id, subject_id, teacher_staff_id, is_primary) " +
            "VALUES (?, ?, ?, ?, ?)", rows);
    }

    // --------------------------------------------------------------- students

    private void seedStudents(JdbcTemplate jdbc, UUID schoolId, String slug, List<String> gradeCodes,
                              List<String> sectionCodes, int perSection, AySeed priorAy, AySeed currentAy) {
        List<Object[]> students = new ArrayList<>();
        List<Object[]> guardians = new ArrayList<>();
        List<Object[]> guardianLinks = new ArrayList<>();
        List<Object[]> guardianAccounts = new ArrayList<>();
        List<Object[]> enrolments = new ArrayList<>();

        int admissionSeq = 1000;
        // user_account.phone is unique chain-wide, so each school gets its own band.
        String phoneBand = PHONE_BANDS.getOrDefault(slug, "96");
        int phoneSeq = 100000;

        for (int gradeIndex = 0; gradeIndex < gradeCodes.size(); gradeIndex++) {
            String gradeCode = gradeCodes.get(gradeIndex);
            for (String sectionCode : sectionCodes) {
                UUID currentSection = sectionId(slug, currentAy.code(), gradeCode, sectionCode);
                for (int i = 0; i < perSection; i++) {
                    admissionSeq++;
                    String studentKey = slug + ":" + gradeCode + sectionCode + ":" + i;
                    UUID studentId = id("student:" + studentKey);
                    String admissionNo = "ADM" + admissionSeq;
                    students.add(new Object[]{
                        studentId, schoolId, admissionNo,
                        "Student" + (i + 1), gradeCode + sectionCode,
                        LocalDate.of(2026 - (6 + gradeIndex), 1 + (i % 12), 1 + (i % 27)),
                        i % 2 == 0 ? "male" : "female"
                    });

                    // Sibling policy: roll-1 of a few grades shares the guardian of roll-1 in an
                    // older grade, so the fixture carries real families. The older sibling is
                    // always a guardian *owner*, never itself a sharer, so the chain cannot
                    // dangle.
                    Integer siblingOfGradeIndex = i == 0 && sectionCode.equals(sectionCodes.get(0))
                        ? SIBLING_GRADE_PAIRS.get(gradeIndex) : null;
                    boolean sharesGuardian = siblingOfGradeIndex != null
                        && siblingOfGradeIndex < gradeCodes.size();
                    UUID guardianId;
                    if (sharesGuardian) {
                        String siblingKey = slug + ":" + gradeCodes.get(siblingOfGradeIndex) + sectionCode + ":0";
                        guardianId = id("guardian:" + siblingKey);
                    } else {
                        guardianId = id("guardian:" + studentKey);
                        phoneSeq++;
                        String phone = "+91" + phoneBand + phoneSeq;
                        guardians.add(new Object[]{
                            guardianId, schoolId, "Guardian" + (i + 1), gradeCode + sectionCode,
                            phone, "guardian." + gradeCode.toLowerCase() + sectionCode.toLowerCase() + i + "@" + slug + ".test"
                        });
                        guardianAccounts.add(new Object[]{
                            id("guardian-user:" + studentKey), schoolId, guardianId, phone
                        });
                    }
                    guardianLinks.add(new Object[]{guardianId, studentId, "father", !sharesGuardian});

                    enrolments.add(new Object[]{
                        id("enrolment:" + currentAy.code() + ":" + studentKey), schoolId, studentId,
                        currentSection, currentAy.id(), currentAy.startsOn(), null, "active",
                        String.format("%02d", i + 1)
                    });

                    // Prior-year history: the student sat in the grade below, and that
                    // enrolment closed as `promoted` at the end of the year.
                    if (gradeIndex > 0) {
                        UUID priorSection = sectionId(slug, priorAy.code(), gradeCodes.get(gradeIndex - 1), sectionCode);
                        enrolments.add(new Object[]{
                            id("enrolment:" + priorAy.code() + ":" + studentKey), schoolId, studentId,
                            priorSection, priorAy.id(), priorAy.startsOn(), priorAy.endsOn(), "promoted",
                            String.format("%02d", i + 1)
                        });
                    }
                }
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO student (id, school_id, admission_no, first_name, last_name, dob, gender) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", students);
        jdbc.batchUpdate(
            "INSERT INTO guardian (id, school_id, first_name, last_name, phone, email) VALUES (?, ?, ?, ?, ?, ?)",
            guardians);
        jdbc.batchUpdate(
            "INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) VALUES (?, ?, ?, ?)",
            guardianLinks);
        jdbc.batchUpdate(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, phone) VALUES (?, ?, 'guardian', ?, ?)",
            guardianAccounts);
        jdbc.batchUpdate(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, ends_on, status, roll_no) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", enrolments);
    }

    /**
     * NFR scenarios need one school at real scale. Off by default — every other
     * scenario runs faster without it. Enable with
     * {@code -Dschoolsoft.cert.bulk-students=2000}: the CBSE school's current year
     * grows extra sections of {@link #BULK_SECTION_SIZE} students until it holds
     * that many active enrolments.
     */
    private void seedBulkStudents(JdbcTemplate jdbc, SchoolSeed school) {
        int target = Integer.getInteger("schoolsoft.cert.bulk-students", 0);
        if (target <= 0) return;

        long existing = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
            Long.class, school.currentAy().id());
        long remaining = target - existing;
        if (remaining <= 0) {
            log.info("Bulk seed: {} active enrolments already present, nothing to add", existing);
            return;
        }
        log.info("Bulk seed: adding {} students to {}", remaining, school.slug());

        UUID gradeId = id(school.slug() + ":grade:9");
        List<Object[]> students = new ArrayList<>();
        List<Object[]> enrolments = new ArrayList<>();
        int sectionIndex = 0;
        int seq = 0;

        while (seq < remaining) {
            sectionIndex++;
            String sectionCode = "N" + sectionIndex;
            UUID sectionId = sectionId(school.slug(), school.currentAy().code(), "9", sectionCode);
            jdbc.update(
                "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, curriculum_id, " +
                "strategy_code, capacity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sectionId, school.id(), gradeId, school.currentAy().id(), sectionCode,
                "Grade 9-" + sectionCode, school.curriculumId(), school.strategyCode(), BULK_SECTION_SIZE);

            for (int i = 0; i < BULK_SECTION_SIZE && seq < remaining; i++, seq++) {
                UUID studentId = id("bulk-student:" + school.slug() + ":" + seq);
                students.add(new Object[]{
                    studentId, school.id(), "BULK" + seq, "Bulk", "Student" + seq,
                    LocalDate.of(2012, 1 + (seq % 12), 1 + (seq % 27)), seq % 2 == 0 ? "male" : "female"
                });
                enrolments.add(new Object[]{
                    id("bulk-enrolment:" + school.slug() + ":" + seq), school.id(), studentId, sectionId,
                    school.currentAy().id(), CURRENT_AY_START, "active", String.format("%03d", i + 1)
                });
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO student (id, school_id, admission_no, first_name, last_name, dob, gender) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", students);
        jdbc.batchUpdate(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, roll_no) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", enrolments);
    }

    // -------------------------------------------------------------- timetable

    private void seedTimetable(JdbcTemplate jdbc, String slug, UUID sectionId,
                               Map<String, UUID> subjectIds, List<UUID> teacherStaffIds, LocalDate effectiveFrom) {
        List<UUID> subjects = List.copyOf(subjectIds.values());
        List<Object[]> rows = new ArrayList<>();
        for (int dow = 1; dow <= 5; dow++) {            // Monday..Friday
            for (int period = 1; period <= 6; period++) {
                UUID subject = subjects.get((dow + period) % subjects.size());
                UUID teacher = teacherStaffIds.get((dow + period) % teacherStaffIds.size());
                rows.add(new Object[]{
                    id("slot:" + sectionId + ":" + dow + ":" + period), sectionId, subject, teacher,
                    dow, period,
                    java.sql.Time.valueOf(String.format("%02d:00:00", 8 + period)),
                    java.sql.Time.valueOf(String.format("%02d:45:00", 8 + period)),
                    "R" + (100 + period), effectiveFrom
                });
            }
        }
        jdbc.batchUpdate(
            "INSERT INTO timetable_slot (id, section_id, subject_id, teacher_staff_id, day_of_week, period_no, " +
            "starts_at, ends_at, room, effective_from) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    // ------------------------------------------------------------- attendance

    private void seedAttendance(JdbcTemplate jdbc, UUID schoolId, UUID sectionId, UUID markedByStaffId,
                                LocalDate from, LocalDate to) {
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no", UUID.class, sectionId);
        List<Object[]> rows = new ArrayList<>();
        int cursor = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            for (UUID studentId : students) {
                cursor++;
                // ~1 absence in 20, 1 late in 33 — enough for percentage assertions
                String status = cursor % 20 == 0 ? "absent" : (cursor % 33 == 0 ? "late" : "present");
                rows.add(new Object[]{
                    id("att:" + sectionId + ":" + date + ":" + studentId), schoolId, studentId, sectionId,
                    date, status, "manual", markedByStaffId
                });
            }
        }
        jdbc.batchUpdate(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, source, marked_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)", rows);
    }

    // ------------------------------------------------------------- assessment

    private void seedAssessmentsAndMarks(JdbcTemplate jdbc, UUID schoolId, String slug, UUID sectionId,
                                         AySeed ay, Map<String, UUID> subjectIds, UUID enteredByStaffId,
                                         String strategyCode, String status) {
        UUID termId = id(slug + ":term:" + ay.code() + ":T1");
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no", UUID.class, sectionId);

        List<Object[]> assessments = new ArrayList<>();
        List<Object[]> components = new ArrayList<>();
        List<Object[]> marks = new ArrayList<>();
        int cursor = 0;

        for (Map.Entry<String, UUID> subject : subjectIds.entrySet()) {
            UUID assessmentId = id("assessment:" + sectionId + ":" + subject.getKey());
            UUID componentId = id("component:" + sectionId + ":" + subject.getKey());
            assessments.add(new Object[]{
                assessmentId, schoolId, sectionId, subject.getValue(), termId, strategyCode,
                "Periodic Test 1 — " + subject.getKey(), "PT", 20, 10,
                ay.startsOn().plusMonths(3), status
            });
            components.add(new Object[]{componentId, assessmentId, "MAIN", "Written Paper", 20, 100});
            for (UUID studentId : students) {
                cursor++;
                double raw = 11 + (cursor % 10);            // 11..20, deterministic
                marks.add(new Object[]{
                    id("mark:" + componentId + ":" + studentId), schoolId, componentId, studentId,
                    raw, enteredByStaffId
                });
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO assessment (id, school_id, section_id, subject_id, term_id, strategy_code, name, " +
            "assessment_type, max_marks, weight_pct, scheduled_on, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            assessments);
        jdbc.batchUpdate(
            "INSERT INTO assessment_component (id, assessment_id, code, name, max_marks, weight_pct) " +
            "VALUES (?, ?, ?, ?, ?, ?)", components);
        jdbc.batchUpdate(
            "INSERT INTO mark (id, school_id, assessment_component_id, student_id, raw_marks, entered_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?)", marks);
    }

    private void seedReportCards(JdbcTemplate jdbc, UUID schoolId, String slug, UUID sectionId,
                                 AySeed ay, String strategyCode) {
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no", UUID.class, sectionId);
        List<Object[]> rows = new ArrayList<>();
        for (UUID studentId : students) {
            rows.add(new Object[]{
                id("reportcard:" + sectionId + ":" + studentId), schoolId, studentId, ay.id(),
                id(slug + ":term:" + ay.code() + ":T2"), strategyCode, strategyCode + "-ANNUAL",
                "{\"summary\":\"Annual report\",\"generatedBy\":\"certification-fixture\"}",
                ay.endsOn().atStartOfDay().atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime()
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO report_card (id, school_id, student_id, academic_year_id, term_id, strategy_code, " +
            "template_code, payload, is_locked, parent_visible_from) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, TRUE, ?)", rows);
    }

    // ------------------------------------------------------------------- fees

    private void seedFees(JdbcTemplate jdbc, UUID schoolId, String slug, Map<String, UUID> grades,
                          AySeed priorAy, AySeed currentAy) {
        Map<String, Integer> heads = new LinkedHashMap<>();
        heads.put("TUITION", 30000);
        heads.put("LAB", 2000);
        heads.put("EXAM", 1500);
        heads.put("TRANSPORT", 6000);

        Map<String, UUID> headIds = new LinkedHashMap<>();
        heads.forEach((code, amount) -> {
            UUID headId = id(slug + ":feehead:" + code);
            headIds.put(code, headId);
            jdbc.update(
                "INSERT INTO fee_head (id, school_id, code, name, is_recurring, gst_rate_pct) VALUES (?, ?, ?, ?, TRUE, 0)",
                headId, schoolId, code, code.charAt(0) + code.substring(1).toLowerCase());
        });

        for (AySeed ay : List.of(priorAy, currentAy)) {
            grades.forEach((gradeCode, gradeId) -> {
                UUID structureId = id(slug + ":feestructure:" + ay.code() + ":" + gradeCode);
                jdbc.update(
                    "INSERT INTO fee_structure (id, school_id, grade_id, academic_year_id, name, schedule) " +
                    "VALUES (?, ?, ?, ?, ?, '{\"cadence\":\"term\",\"instalments\":2}'::jsonb)",
                    structureId, schoolId, gradeId, ay.id(), "Standard " + ay.code());
                headIds.forEach((code, headId) -> {
                    if (code.equals("TRANSPORT")) return;   // opt-in head, billed per assignment
                    jdbc.update(
                        "INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) VALUES (?, ?, ?, ?)",
                        id(slug + ":feeline:" + ay.code() + ":" + gradeCode + ":" + code),
                        structureId, headId, heads.get(code));
                });
            });
        }

        seedInvoices(jdbc, schoolId, slug, priorAy, headIds.get("TUITION"), true);
        seedInvoices(jdbc, schoolId, slug, currentAy, headIds.get("TUITION"), false);
    }

    /**
     * One tuition invoice per enrolled student for the year. Prior-year invoices
     * are fully paid (with balanced ledger legs); current-year invoices are open,
     * and every fifth one is left past its due date so dunning scenarios have
     * something real to act on.
     */
    private void seedInvoices(JdbcTemplate jdbc, UUID schoolId, String slug, AySeed ay,
                              UUID tuitionHeadId, boolean paid) {
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE academic_year_id = ? AND school_id = ? ORDER BY id",
            UUID.class, ay.id(), schoolId);

        List<Object[]> invoices = new ArrayList<>();
        List<Object[]> lines = new ArrayList<>();
        List<Object[]> payments = new ArrayList<>();
        List<Object[]> ledger = new ArrayList<>();

        int seq = 0;
        for (UUID studentId : students) {
            seq++;
            UUID invoiceId = id("invoice:" + ay.code() + ":" + studentId);
            String invoiceNo = "INV-" + ay.code() + "-" + String.format("%05d", seq);
            LocalDate dueOn = paid ? ay.startsOn().plusMonths(1) : LocalDate.of(2026, 7, 10);
            boolean overdueCandidate = !paid && seq % 5 == 0;
            String status = paid ? "paid" : (overdueCandidate ? "open" : "open");
            invoices.add(new Object[]{
                invoiceId, schoolId, studentId, invoiceNo, "Term 1 " + ay.code(),
                ay.startsOn(), overdueCandidate ? dueOn : dueOn.plusMonths(2),
                30000, 0, 30000, paid ? 30000 : 0, status
            });
            lines.add(new Object[]{
                id("invoiceline:" + invoiceId), invoiceId, tuitionHeadId, "Tuition — Term 1", 30000
            });
            if (paid) {
                UUID paymentId = id("payment:" + invoiceId);
                payments.add(new Object[]{
                    paymentId, schoolId, invoiceId, 30000, "cash", "captured",
                    "cert-" + slug + "-" + invoiceNo, ay.startsOn().plusMonths(1).atStartOfDay()
                        .atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime()
                });
                UUID journalId = id("journal:" + invoiceId);
                ledger.add(new Object[]{id("ledger:" + invoiceId + ":dr"), schoolId, journalId, "BANK",
                    30000, 0, "Fee receipt " + invoiceNo, "payment", paymentId});
                ledger.add(new Object[]{id("ledger:" + invoiceId + ":cr"), schoolId, journalId, "FEE_RECEIVABLE",
                    0, 30000, "Fee receipt " + invoiceNo, "payment", paymentId});
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO fee_invoice (id, school_id, student_id, invoice_no, cycle_label, issued_on, due_on, " +
            "subtotal, gst, total, paid, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", invoices);
        jdbc.batchUpdate(
            "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount) VALUES (?, ?, ?, ?, ?)",
            lines);
        jdbc.batchUpdate(
            "INSERT INTO payment (id, school_id, fee_invoice_id, amount, gateway, status, idempotency_key, captured_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", payments);
        jdbc.batchUpdate(
            "INSERT INTO ledger_entry (id, school_id, journal_id, account_code, debit, credit, narration, source_type, source_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", ledger);
    }

    // ---------------------------------------------------------------- library

    private void seedLibrary(JdbcTemplate jdbc, UUID schoolId, String slug, UUID focusSectionId) {
        UUID titleId = id(slug + ":librarytitle:1");
        jdbc.update(
            "INSERT INTO library_title (id, school_id, isbn, title, author, publisher, year) " +
            "VALUES (?, ?, '9780143441724', 'The Blue Umbrella', 'Ruskin Bond', 'Penguin', 2012)",
            titleId, schoolId);
        for (int i = 1; i <= 3; i++) {
            jdbc.update(
                "INSERT INTO library_copy (id, title_id, barcode, status) VALUES (?, ?, ?, ?)",
                id(slug + ":librarycopy:" + i), titleId, slug.toUpperCase() + "-BC-" + i,
                i == 1 ? "issued" : "available");
        }
        UUID borrower = jdbc.queryForObject(
            "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no LIMIT 1",
            UUID.class, focusSectionId);
        jdbc.update(
            "INSERT INTO library_issue (id, school_id, copy_id, member_type, member_id, issued_on, due_on) " +
            "VALUES (?, ?, ?, 'student', ?, ?, ?)",
            id(slug + ":libraryissue:1"), schoolId, id(slug + ":librarycopy:1"), borrower,
            TODAY.minusDays(30), TODAY.minusDays(16));
    }

    // -------------------------------------------------------------- transport

    private void seedTransport(JdbcTemplate jdbc, UUID schoolId, String slug, UUID focusSectionId) {
        UUID vehicleId = id(slug + ":vehicle:1");
        UUID driverId = id(slug + ":driver:1");
        UUID routeId = id(slug + ":route:1");
        jdbc.update(
            "INSERT INTO vehicle (id, school_id, registration_no, model, capacity) VALUES (?, ?, ?, 'Tata Starbus', 40)",
            vehicleId, schoolId, "TS09UB" + (slug.equals("oakridge") ? "1234" : "5678"));
        jdbc.update(
            "INSERT INTO driver (id, school_id, name, phone, license_no) VALUES (?, ?, 'Ramesh Yadav', '+919812345678', 'TS0120200012345')",
            driverId, schoolId);
        jdbc.update(
            "INSERT INTO transport_route (id, school_id, code, name, direction) VALUES (?, ?, 'R1', 'Route 1 — Jubilee Hills', 'both')",
            routeId, schoolId);
        for (int i = 1; i <= 3; i++) {
            jdbc.update(
                "INSERT INTO transport_stop (id, school_id, route_id, name, sort_order, lat, lng, pickup_time, fee) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id(slug + ":stop:" + i), schoolId, routeId, "Stop " + i, i,
                17.40 + i * 0.01, 78.40 + i * 0.01, java.sql.Time.valueOf("07:" + (10 * i) + ":00"), 6000);
        }
        jdbc.update(
            "INSERT INTO route_assignment (id, route_id, vehicle_id, driver_id, effective_from) VALUES (?, ?, ?, ?, ?)",
            id(slug + ":routeassignment:1"), routeId, vehicleId, driverId, CURRENT_AY_START);

        List<UUID> riders = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no LIMIT 4",
            UUID.class, focusSectionId);
        List<Object[]> assignments = new ArrayList<>();
        for (UUID studentId : riders) {
            assignments.add(new Object[]{
                id("studenttransport:" + studentId), schoolId, studentId, routeId, id(slug + ":stop:1"),
                CURRENT_AY_START
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO student_transport (id, school_id, student_id, route_id, stop_id, starts_on) " +
            "VALUES (?, ?, ?, ?, ?, ?)", assignments);
    }

    // ---------------------------------------------------------------- helpers

    /** Deterministic id from a natural key, so a failing run is reproducible. */
    public static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("schoolsoft-cert:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID sectionId(String schoolSlug, String ayCode, String gradeCode, String sectionCode) {
        return id(schoolSlug + ":section:" + ayCode + ":" + gradeCode + ":" + sectionCode);
    }

    private <T> T inChain(UUID chainId, java.util.function.Function<JdbcTemplate, T> body) {
        TenantContext.set(TenantContext.trustedJob(CHAIN_SCHEMA, chainId));
        try {
            return body.apply(new JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }
}

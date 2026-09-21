package com.schoolsoft.tenancy.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a school needs before it can open, and the one copy of each question.
 *
 * <p>Every step answers itself out of the rows that would exist if it were
 * done — "has this school any sections in its current year" is a question
 * about sections. Nothing here is a stored flag, because a flag beside the
 * answer is a second copy of it: delete the last section and a flag still
 * says the step is finished, which is exactly the state a setup checklist
 * exists to catch.</p>
 *
 * <h2>Blocking and not</h2>
 * A {@code blocking} step is one whose absence makes the school unusable to
 * the people who would be let in — no section is nowhere to put a child, no
 * account is nobody to let in at all. The rest are real work that a school can
 * reasonably do in its first week, so they are listed, counted and never stand
 * between a school and its own front door.
 *
 * <p>The probes take the school id and nothing else; where a step is about the
 * <em>current</em> academic year, the query reaches that year itself rather
 * than making every caller look it up and pass it in.</p>
 */
public enum OnboardingStep {

    CAMPUS("campus", "Campus", true, "campus", "campuses",
        "Every section, staff member and timetable hangs off a campus, and a school with "
            + "none has nowhere to put them.",
        "SELECT count(*) FROM campus WHERE school_id = ?"),

    ACADEMIC_YEAR("academic_year", "Academic year", true, "academic year", "academic years",
        "The year is the frame every date-scoped read asks its question inside. Without a "
            + "current one, attendance, marks and fees all answer empty and none of them says why.",
        "SELECT count(*) FROM academic_year WHERE school_id = ? AND is_current"),

    TERMS("terms", "Terms", true, "term", "terms",
        "Report cards, fee schedules and exam cycles are all reported by term.",
        "SELECT count(*) FROM term t JOIN academic_year ay ON ay.id = t.academic_year_id "
            + "WHERE ay.school_id = ? AND ay.is_current"),

    GRADES("grades", "Grades", true, "grade", "grades",
        "The ladder a child is admitted into and promoted up.",
        "SELECT count(*) FROM grade WHERE school_id = ?"),

    SECTIONS("sections", "Sections", true, "section", "sections",
        "A grade with no section has nowhere to put a child, and admissions cannot offer a "
            + "seat it cannot name.",
        "SELECT count(*) FROM section s WHERE s.school_id = ? AND EXISTS ("
            + "SELECT 1 FROM academic_year ay WHERE ay.id = s.academic_year_id AND ay.is_current)"),

    SUBJECTS("subjects", "Subjects", true, "subject", "subjects",
        "Nothing to timetable and nothing to mark until these exist.",
        "SELECT count(*) FROM subject WHERE school_id = ?"),

    /**
     * Asked of the grants rather than of the role names: a school that built a
     * custom role holding {@code structure.manage} has somebody who can run
     * the place, and this step should say so without a deploy.
     */
    ADMIN_ACCOUNT("admin_account", "Somebody who can run the school", true, "account", "accounts",
        "A school handed over with no account that can manage its structure is a school "
            + "nobody can get into.",
        "SELECT count(*) FROM user_account ua "
            + "JOIN staff s ON s.id = ua.subject_id AND s.is_active "
            + "JOIN staff_role sr ON sr.staff_id = s.id AND sr.revoked_at IS NULL "
            + "JOIN role_perm rp ON rp.role_code = sr.role_code AND rp.perm_code = 'structure.manage' "
            + "WHERE ua.school_id = ? AND ua.subject_type = 'staff' AND ua.is_active"),

    FEE_STRUCTURE("fee_structure", "Fee structure", false, "fee structure", "fee structures",
        "Invoices cannot be raised until a structure exists, but a school can open its doors "
            + "and bill in its first week — or leave the billing to the chain.",
        "SELECT count(*) FROM fee_structure fs WHERE fs.school_id = ? AND EXISTS ("
            + "SELECT 1 FROM academic_year ay WHERE ay.id = fs.academic_year_id AND ay.is_current)"),

    WORKING_WEEK("working_week", "Working week", false, "pattern", "patterns",
        "Which days are taught is the denominator under every attendance percentage. Until "
            + "it is set the school runs on the six-day default.",
        "SELECT count(*) FROM working_day_pattern WHERE school_id = ?"),

    THEME("theme", "Theme", false, "theme", "themes",
        "The school's colours and app names, as families see them. Unset means the chain's.",
        "SELECT count(*) FROM school_theme WHERE school_id = ?");

    private final String key;
    private final String label;
    private final boolean blocking;
    private final String unitOne;
    private final String unitMany;
    private final String why;
    private final String probe;

    OnboardingStep(String key, String label, boolean blocking, String unitOne, String unitMany,
                   String why, String probe) {
        this.key = key;
        this.label = label;
        this.blocking = blocking;
        this.unitOne = unitOne;
        this.unitMany = unitMany;
        this.why = why;
        this.probe = probe;
    }

    public String key() { return key; }

    public String label() { return label; }

    /** True when a school cannot open without it. */
    public boolean blocking() { return blocking; }

    /**
     * What the step counts, named. A screen showing "7 subjects" is easier to
     * believe than one showing "7 rows", and English plurals are not a rule a
     * client should be made to guess at — "campuses" is the example.
     */
    public String unit(long count) { return count == 1 ? unitOne : unitMany; }

    /** Why it matters, in the words the checklist shows. */
    public String why() { return why; }

    /** {@code SELECT count(*) …} over the rows that make this step done; one bind, the school id. */
    String probe() { return probe; }

    public static Optional<OnboardingStep> byKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }
}

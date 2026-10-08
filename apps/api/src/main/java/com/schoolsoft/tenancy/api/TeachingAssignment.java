package com.schoolsoft.tenancy.api;

/**
 * "Who teaches this subject in this section?" is a question about a date.
 *
 * <p>A {@code section_subject_teacher} row carries
 * {@code effective_from}/{@code effective_to}, both inclusive and both open
 * when NULL, so a teacher who leaves hands a section over from a day rather
 * than by having their name overwritten on the day the paperwork is filed.
 * Every read that asks <em>who</em> applies the window; this is the one copy
 * of it.</p>
 *
 * <p>A read that asks <em>what the section is taught</em> — the compulsory
 * subject set, whether a subject is an elective here — does not: the subject
 * carries on when its teacher changes.</p>
 */
public final class TeachingAssignment {

    private TeachingAssignment() {}

    /**
     * The predicate, for a {@code section_subject_teacher} aliased
     * {@code alias}. Takes two positional parameters — the date, twice.
     */
    public static String inForce(String alias) {
        return "(COALESCE(" + alias + ".effective_from, '-infinity'::date) <= ? " +
               "AND COALESCE(" + alias + ".effective_to, 'infinity'::date) >= ?)";
    }

    /** As {@link #inForce}, for a statement whose parameter list is already spoken for. */
    public static String inForceOn(String alias, java.time.LocalDate date) {
        return "(COALESCE(" + alias + ".effective_from, '-infinity'::date) <= DATE '" + date + "' " +
               "AND COALESCE(" + alias + ".effective_to, 'infinity'::date) >= DATE '" + date + "')";
    }
}

package com.schoolsoft.tenancy.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generator for every human-facing number: admission numbers, roll
 * numbers, invoice and receipt numbers, certificate serials (GAP-26).
 *
 * A series is (school, kind, scope) — roll numbers run per section, invoices
 * per school. The pattern is a template:
 *
 * <pre>
 *   {YY}     two-digit calendar year        {YYYY}  four-digit year
 *   {AY}     academic year code, when given {SEQ}   the counter
 *   {SEQ:4}  the counter zero-padded to 4
 * </pre>
 *
 * The counter is taken under a row lock, so two registrars admitting a student
 * at the same moment cannot be handed the same number — the failure mode that
 * makes hand-rolled sequences unusable in a school office.
 */
@Service
public class NumberSeries {

    public enum Kind { admission, roll, invoice, receipt, certificate }

    private final JdbcTemplate jdbc;

    public NumberSeries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Next number in the series, creating it from {@code defaultPattern} if the
     * school has not configured one. Runs in its own transaction so the counter
     * advances even if the caller's work later rolls back — a gap in a number
     * series is a nuisance, a duplicate is a defect.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(UUID schoolId, Kind kind, UUID scopeId, String defaultPattern, Map<String, String> vars) {
        return next(schoolId, kind, scopeId, defaultPattern, vars, 1L);
    }

    /**
     * As {@link #next}, but a series created here starts at
     * {@code initialValue} — used where numbers already exist and the generator
     * must not hand out one the school issued by hand (roll numbers in a
     * section that was seeded before the series existed).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(UUID schoolId, Kind kind, UUID scopeId, String defaultPattern,
                       Map<String, String> vars, long initialValue) {
        List<Object[]> rows = jdbc.query(
            "SELECT id, pattern, next_value, reset_policy, last_reset_year FROM number_series " +
            "WHERE school_id = ? AND kind = ? AND scope_id IS NOT DISTINCT FROM ? FOR UPDATE",
            (rs, i) -> new Object[]{
                UUID.fromString(rs.getString("id")), rs.getString("pattern"), rs.getLong("next_value"),
                rs.getString("reset_policy"), (Integer) rs.getObject("last_reset_year")
            },
            schoolId, kind.name(), scopeId);

        UUID seriesId;
        String pattern;
        long value;
        String resetPolicy;
        Integer lastResetYear;
        if (rows.isEmpty()) {
            seriesId = UUID.randomUUID();
            pattern = defaultPattern;
            value = Math.max(1L, initialValue);
            resetPolicy = "never";
            lastResetYear = null;
            jdbc.update(
                "INSERT INTO number_series (id, school_id, kind, scope_id, pattern, next_value, reset_policy) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'never')",
                seriesId, schoolId, kind.name(), scopeId, pattern, value);
        } else {
            seriesId = (UUID) rows.get(0)[0];
            pattern = (String) rows.get(0)[1];
            value = (Long) rows.get(0)[2];
            resetPolicy = (String) rows.get(0)[3];
            lastResetYear = (Integer) rows.get(0)[4];
        }

        int year = LocalDate.now().getYear();
        if ("yearly".equals(resetPolicy) && (lastResetYear == null || lastResetYear != year)) {
            value = 1L;
            jdbc.update("UPDATE number_series SET last_reset_year = ? WHERE id = ?", year, seriesId);
        }

        jdbc.update("UPDATE number_series SET next_value = ? WHERE id = ?", value + 1, seriesId);
        return render(pattern, value, vars);
    }

    /** Peeks without consuming — for a UI that previews the next number. */
    public String preview(UUID schoolId, Kind kind, UUID scopeId, String defaultPattern, Map<String, String> vars) {
        var rows = jdbc.query(
            "SELECT pattern, next_value FROM number_series " +
            "WHERE school_id = ? AND kind = ? AND scope_id IS NOT DISTINCT FROM ?",
            (rs, i) -> new Object[]{ rs.getString("pattern"), rs.getLong("next_value") },
            schoolId, kind.name(), scopeId);
        if (rows.isEmpty()) return render(defaultPattern, 1L, vars);
        return render((String) rows.get(0)[0], (Long) rows.get(0)[1], vars);
    }

    static String render(String pattern, long value, Map<String, String> vars) {
        String out = pattern;
        LocalDate today = LocalDate.now();
        out = out.replace("{YYYY}", String.valueOf(today.getYear()));
        out = out.replace("{YY}", String.format("%02d", today.getYear() % 100));
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        // {SEQ:n} pads; bare {SEQ} does not.
        java.util.regex.Matcher padded = java.util.regex.Pattern.compile("\\{SEQ:(\\d+)}").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (padded.find()) {
            int width = Integer.parseInt(padded.group(1));
            padded.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                String.format("%0" + width + "d", value)));
        }
        padded.appendTail(sb);
        return sb.toString().replace("{SEQ}", String.valueOf(value));
    }
}

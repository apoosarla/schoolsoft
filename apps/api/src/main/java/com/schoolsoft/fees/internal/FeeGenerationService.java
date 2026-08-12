package com.schoolsoft.fees.internal;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk invoice generation from a fee structure (FEE-02).
 *
 * The run is keyed on (school, academic year, cycle, grade) and the invoice
 * table carries a unique index on (student, cycle): re-running October is a
 * no-op, which is the property that makes it safe to retry a run that died
 * halfway through billing 2,000 children.
 *
 * One invoice is assembled per student from four sources, in this order:
 *
 * <ol>
 *   <li>the grade's fee structure lines;</li>
 *   <li>the student's transport assignment, if the route carries a fee and the
 *       assignment covers this cycle (FEE-12);</li>
 *   <li>their own concessions, as visible negative lines rather than a quietly
 *       reduced total (FEE-03);</li>
 *   <li>the sibling concession their position in the family earns (FEE-04).</li>
 * </ol>
 *
 * GST is computed per head from {@code fee_head.gst_rate_pct} on the net amount
 * after discounts (FEE-13).
 */
@Service
public class FeeGenerationService {

    private final JdbcTemplate jdbc;
    private final NumberSeries numbers;
    private final WorkingDayService workingDays;

    public FeeGenerationService(JdbcTemplate jdbc, NumberSeries numbers, WorkingDayService workingDays) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.workingDays = workingDays;
    }

    public record RunResult(UUID runId, int invoicesCreated, int studentsSkipped, double totalBilled,
                            boolean alreadyRun) {}

    /**
     * Generates the cycle's invoices. Returns {@code alreadyRun} when this
     * cycle was billed before — the caller sees a no-op, not an error, because
     * a retry is a normal thing for an operator to do.
     */
    @Transactional
    public RunResult generate(UUID schoolId, UUID academicYearId, UUID gradeId, String cycleLabel,
                              LocalDate dueOn, UUID runByStaffId) {
        var existing = jdbc.query(
            "SELECT id, invoices_created, students_skipped, total_billed FROM fee_schedule_run " +
            "WHERE school_id = ? AND academic_year_id = ? AND cycle_label = ? " +
            "  AND grade_id IS NOT DISTINCT FROM ? AND state = 'completed'",
            (rs, i) -> new RunResult(UUID.fromString(rs.getString("id")), rs.getInt("invoices_created"),
                rs.getInt("students_skipped"), rs.getDouble("total_billed"), true),
            schoolId, academicYearId, cycleLabel, gradeId);
        if (!existing.isEmpty()) return existing.get(0);

        LocalDate due = workingDays.nextWorkingDayOnOrAfter(schoolId, dueOn, null, null);

        UUID runId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_schedule_run (id, school_id, academic_year_id, cycle_label, grade_id, due_on, " +
            "  state, run_by_staff_id) VALUES (?, ?, ?, ?, ?, ?, 'running', ?) " +
            "ON CONFLICT (school_id, academic_year_id, cycle_label, grade_id) DO UPDATE SET " +
            "  state = 'running', due_on = EXCLUDED.due_on, created_at = now()",
            runId, schoolId, academicYearId, cycleLabel, gradeId, Date.valueOf(due), runByStaffId);
        runId = jdbc.queryForObject(
            "SELECT id FROM fee_schedule_run WHERE school_id = ? AND academic_year_id = ? " +
            "  AND cycle_label = ? AND grade_id IS NOT DISTINCT FROM ?",
            UUID.class, schoolId, academicYearId, cycleLabel, gradeId);

        int created = 0;
        int skipped = 0;
        double billed = 0;
        for (Enrolled student : enrolledStudents(schoolId, academicYearId, gradeId)) {
            Integer already = jdbc.queryForObject(
                "SELECT count(*) FROM fee_invoice WHERE student_id = ? AND cycle_label = ? " +
                "  AND fee_schedule_run_id IS NOT NULL AND status <> 'cancelled'",
                Integer.class, student.studentId(), cycleLabel);
            if (already != null && already > 0) {
                skipped++;
                continue;
            }
            double total = createInvoiceFor(student, schoolId, academicYearId, cycleLabel, due, runId);
            if (total < 0) {
                skipped++;                                  // no structure for their grade
            } else {
                created++;
                billed += total;
            }
        }

        jdbc.update(
            "UPDATE fee_schedule_run SET state = 'completed', invoices_created = ?, students_skipped = ?, " +
            "  total_billed = ? WHERE id = ?",
            created, skipped, billed, runId);
        return new RunResult(runId, created, skipped, billed, false);
    }

    private record Enrolled(UUID studentId, UUID gradeId, UUID familyId, LocalDate admittedOn) {}

    private List<Enrolled> enrolledStudents(UUID schoolId, UUID academicYearId, UUID gradeId) {
        StringBuilder sql = new StringBuilder(
            "SELECT st.id AS student_id, sec.grade_id, st.family_id, e.starts_on " +
            "FROM enrolment e JOIN section sec ON sec.id = e.section_id " +
            "JOIN student st ON st.id = e.student_id " +
            "WHERE e.school_id = ? AND e.academic_year_id = ? AND e.status = 'active'");
        List<Object> args = new ArrayList<>(List.of(schoolId, academicYearId));
        if (gradeId != null) {
            sql.append(" AND sec.grade_id = ?");
            args.add(gradeId);
        }
        sql.append(" ORDER BY st.admission_no");
        return jdbc.query(sql.toString(), (rs, i) -> new Enrolled(
            UUID.fromString(rs.getString("student_id")),
            UUID.fromString(rs.getString("grade_id")),
            rs.getString("family_id") == null ? null : UUID.fromString(rs.getString("family_id")),
            rs.getDate("starts_on").toLocalDate()), args.toArray());
    }

    /** Returns the invoice total, or -1 when the grade has no structure to bill from. */
    private double createInvoiceFor(Enrolled student, UUID schoolId, UUID academicYearId,
                                    String cycleLabel, LocalDate dueOn, UUID runId) {
        var structureLines = jdbc.query(
            "SELECT l.fee_head_id, h.code, h.name, l.amount, h.gst_rate_pct " +
            "FROM fee_structure s JOIN fee_structure_line l ON l.fee_structure_id = s.id " +
            "JOIN fee_head h ON h.id = l.fee_head_id " +
            "WHERE s.school_id = ? AND s.grade_id = ? AND s.academic_year_id = ? ORDER BY h.code",
            (rs, i) -> new Charge(UUID.fromString(rs.getString("fee_head_id")), rs.getString("code"),
                rs.getString("name"), rs.getDouble("amount"), rs.getDouble("gst_rate_pct"), "structure"),
            schoolId, student.gradeId(), academicYearId);
        if (structureLines.isEmpty()) return -1;

        List<Charge> charges = new ArrayList<>(structureLines);
        transportCharge(schoolId, student.studentId(), dueOn).ifPresent(charges::add);

        double subtotal = 0;
        double gst = 0;
        List<Object[]> lineRows = new ArrayList<>();
        UUID invoiceId = UUID.randomUUID();

        for (Charge charge : charges) {
            double discount = concessionFor(schoolId, student.studentId(), academicYearId,
                charge.feeHeadId(), charge.amount())
                + siblingConcessionFor(schoolId, student, academicYearId, charge.feeHeadId(), charge.amount());
            discount = Math.min(discount, charge.amount());
            double net = charge.amount() - discount;
            double lineGst = round(net * charge.gstRatePct() / 100.0);

            subtotal += net;
            gst += lineGst;
            lineRows.add(new Object[]{
                UUID.randomUUID(), invoiceId, charge.feeHeadId(), charge.description(),
                charge.amount(), discount, lineGst, charge.source(), student.studentId()
            });
        }

        double total = round(subtotal + gst);
        String invoiceNo = numbers.next(schoolId, NumberSeries.Kind.invoice, null, "INV{YY}{SEQ:5}", null);
        jdbc.update(
            "INSERT INTO fee_invoice (id, school_id, student_id, academic_year_id, invoice_no, cycle_label, " +
            "  issued_on, due_on, subtotal, gst, total, status, fee_schedule_run_id, family_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, ?, ?, ?, 'open', ?, ?)",
            invoiceId, schoolId, student.studentId(), academicYearId, invoiceNo, cycleLabel,
            Date.valueOf(dueOn), round(subtotal), round(gst), total, runId, student.familyId());

        jdbc.batchUpdate(
            "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount, discount, " +
            "  gst, source, student_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            lineRows);
        return total;
    }

    private record Charge(UUID feeHeadId, String code, String name, double amount, double gstRatePct,
                          String source) {
        String description() { return name; }
    }

    /**
     * Transport is billed only for a student whose route assignment covers the
     * cycle, at the route's own rate — so dropping a route stops the charge on
     * the next run rather than needing a manual correction (FEE-12, TRN-06).
     */
    private java.util.Optional<Charge> transportCharge(UUID schoolId, UUID studentId, LocalDate onDate) {
        var rows = jdbc.query(
            "SELECT r.monthly_fee, COALESCE(r.fee_head_id, h.id) AS head_id, " +
            "       COALESCE(h2.code, h.code) AS code, COALESCE(h2.name, h.name) AS name, " +
            "       COALESCE(h2.gst_rate_pct, h.gst_rate_pct) AS gst_rate_pct " +
            "FROM student_transport t JOIN transport_route r ON r.id = t.route_id " +
            "LEFT JOIN fee_head h ON h.school_id = ? AND h.code = 'TRANSPORT' " +
            "LEFT JOIN fee_head h2 ON h2.id = r.fee_head_id " +
            "WHERE t.student_id = ? AND r.monthly_fee IS NOT NULL " +
            "  AND t.starts_on <= ? AND COALESCE(t.ends_on, 'infinity'::date) >= ? " +
            "ORDER BY t.starts_on DESC LIMIT 1",
            (rs, i) -> rs.getString("head_id") == null ? null : new Charge(
                UUID.fromString(rs.getString("head_id")), rs.getString("code"), rs.getString("name"),
                rs.getDouble("monthly_fee"), rs.getDouble("gst_rate_pct"), "transport"),
            schoolId, studentId, Date.valueOf(onDate), Date.valueOf(onDate));
        return rows.isEmpty() || rows.get(0) == null ? java.util.Optional.empty()
            : java.util.Optional.of(rows.get(0));
    }

    /** A student's own concessions for this head, as an amount off (FEE-03). */
    private double concessionFor(UUID schoolId, UUID studentId, UUID academicYearId, UUID feeHeadId,
                                 double amount) {
        Double discount = jdbc.queryForObject(
            "SELECT COALESCE(sum(COALESCE(c.flat_amount, 0) + COALESCE(c.pct, 0) * ? / 100.0), 0) " +
            "FROM fee_concession c WHERE c.school_id = ? AND c.student_id = ? AND c.academic_year_id = ? " +
            "  AND (c.applies_to_head_id IS NULL OR c.applies_to_head_id = ?) AND c.kind <> 'sibling'",
            Double.class, amount, schoolId, studentId, academicYearId, feeHeadId);
        return round(discount == null ? 0 : discount);
    }

    /**
     * The sibling rule, applied by birth order within the family: the eldest
     * pays full, the second pays the policy's percentage less, and so on. The
     * order comes from enrolment start, so admitting a younger child does not
     * re-price the elder one mid-year.
     */
    private double siblingConcessionFor(UUID schoolId, Enrolled student, UUID academicYearId,
                                        UUID feeHeadId, double amount) {
        if (student.familyId() == null) return 0;
        // Birth order within the household: the child who joined first, and on a
        // tie the one in the higher grade, is the eldest. Enrolment dates alone
        // are not enough — siblings enrolled on the same day are the normal case
        // at the start of a year.
        var ranks = jdbc.query(
            "WITH household AS (" +
            "  SELECT sib.id, row_number() OVER (" +
            "    ORDER BY e.starts_on, g.sort_order DESC, sib.admission_no) AS rn " +
            "  FROM student sib " +
            "  JOIN enrolment e ON e.student_id = sib.id AND e.status = 'active' " +
            "    AND e.academic_year_id = ? " +
            "  JOIN section sec ON sec.id = e.section_id " +
            "  JOIN grade g ON g.id = sec.grade_id " +
            "  WHERE sib.family_id = ?) " +
            "SELECT rn FROM household WHERE id = ?",
            (rs, i) -> rs.getInt("rn"), academicYearId, student.familyId(), student.studentId());
        Integer rank = ranks.isEmpty() ? null : ranks.get(0);
        if (rank == null || rank < 2) return 0;

        Double pct = jdbc.queryForObject(
            "SELECT COALESCE(max(pct), 0) FROM sibling_concession_policy " +
            "WHERE school_id = ? AND academic_year_id = ? AND nth_child <= ? " +
            "  AND (applies_to_head_id IS NULL OR applies_to_head_id = ?)",
            Double.class, schoolId, academicYearId, rank, feeHeadId);
        return pct == null || pct == 0 ? 0 : round(amount * pct / 100.0);
    }

    /**
     * One bill for a household (FEE-04): a family invoice that carries every
     * open child invoice's balance as a line, so a parent with three children
     * pays once. The child invoices stay — they are what the ledger and the
     * report card's dues check read — and are settled as the family invoice is
     * paid.
     */
    @Transactional
    public UUID createCombinedFamilyInvoice(UUID schoolId, UUID familyId, String cycleLabel, LocalDate dueOn) {
        var open = jdbc.query(
            "SELECT fi.id, fi.student_id, fi.invoice_no, (fi.total - fi.paid) AS balance, " +
            "       (st.first_name || ' ' || COALESCE(st.last_name, '')) AS student_name " +
            "FROM fee_invoice fi JOIN student st ON st.id = fi.student_id " +
            "WHERE st.family_id = ? AND fi.status IN ('open','partial','overdue') AND fi.total > fi.paid " +
            "ORDER BY st.admission_no",
            (rs, i) -> new Object[]{
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("student_id")),
                rs.getString("invoice_no"), rs.getDouble("balance"), rs.getString("student_name")
            }, familyId);
        if (open.isEmpty()) {
            throw new IllegalArgumentException("Family has no outstanding invoices to combine");
        }

        UUID feeHeadId = jdbc.queryForObject(
            "SELECT id FROM fee_head WHERE school_id = ? ORDER BY (code = 'TUITION') DESC, code LIMIT 1",
            UUID.class, schoolId);
        double total = open.stream().mapToDouble(row -> (Double) row[3]).sum();

        UUID invoiceId = UUID.randomUUID();
        String invoiceNo = numbers.next(schoolId, NumberSeries.Kind.invoice, null, "FAM{YY}{SEQ:5}", null);
        jdbc.update(
            "INSERT INTO fee_invoice (id, school_id, student_id, family_id, invoice_no, cycle_label, " +
            "  issued_on, due_on, subtotal, gst, total, status) " +
            "VALUES (?, ?, NULL, ?, ?, ?, CURRENT_DATE, ?, ?, 0, ?, 'open')",
            invoiceId, schoolId, familyId, invoiceNo, cycleLabel, Date.valueOf(dueOn),
            round(total), round(total));
        for (Object[] row : open) {
            jdbc.update(
                "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount, " +
                "  discount, gst, source, student_id) VALUES (?, ?, ?, ?, ?, 0, 0, 'manual', ?)",
                UUID.randomUUID(), invoiceId, feeHeadId,
                row[4] + " — " + row[2], row[3], row[1]);
        }
        return invoiceId;
    }

    /** Family a student belongs to, created on demand around their primary guardian. */
    public UUID familyForStudent(UUID schoolId, UUID studentId) {
        var existing = jdbc.query("SELECT family_id FROM student WHERE id = ?",
            (rs, i) -> rs.getString("family_id") == null ? null : UUID.fromString(rs.getString("family_id")),
            studentId);
        if (existing.isEmpty()) throw new NotFoundException("Student not found: " + studentId);
        if (existing.get(0) != null) return existing.get(0);

        var guardian = jdbc.query(
            "SELECT g.id, g.first_name, g.last_name, g.phone FROM guardian g " +
            "JOIN guardian_student gs ON gs.guardian_id = g.id " +
            "WHERE gs.student_id = ? ORDER BY gs.is_primary DESC LIMIT 1",
            (rs, i) -> new Object[]{ UUID.fromString(rs.getString("id")),
                (rs.getString("first_name") + " " + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim(),
                rs.getString("phone") },
            studentId);
        if (guardian.isEmpty()) {
            throw new IllegalArgumentException("Student has no guardian to build a family around: " + studentId);
        }
        UUID guardianId = (UUID) guardian.get(0)[0];

        // Every child of that guardian belongs to the same household.
        var sameGuardian = jdbc.query(
            "SELECT DISTINCT s.family_id FROM student s " +
            "JOIN guardian_student gs ON gs.student_id = s.id " +
            "WHERE gs.guardian_id = ? AND s.family_id IS NOT NULL LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("family_id")), guardianId);

        UUID familyId;
        if (!sameGuardian.isEmpty()) {
            familyId = sameGuardian.get(0);
        } else {
            familyId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO family (id, school_id, code, name, primary_guardian_id) VALUES (?, ?, ?, ?, ?)",
                familyId, schoolId, "FAM-" + guardianId.toString().substring(0, 8),
                guardian.get(0)[1] + " family", guardianId);
        }
        jdbc.update(
            "UPDATE student SET family_id = ? WHERE id IN (" +
            "  SELECT gs.student_id FROM guardian_student gs WHERE gs.guardian_id = ?)",
            familyId, guardianId);
        return familyId;
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

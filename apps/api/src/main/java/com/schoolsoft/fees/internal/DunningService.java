package com.schoolsoft.fees.internal;

import com.schoolsoft.jobs.api.TenantJobRunner;
import com.schoolsoft.notification.api.NotificationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Moves unpaid invoices past their due date to {@code overdue}, sends the
 * school's configured reminders, and applies a late fee once the grace period
 * has run out (FEE-09, FEE-10).
 *
 * Every action is recorded in {@code dunning_event} keyed on (invoice, kind,
 * day offset), so running the job twice in a day — or re-running it after a
 * failure — does not send a family the same reminder twice. That property
 * matters more here than anywhere else in the fee engine: duplicate dunning is
 * how a school loses a parent's goodwill.
 */
@Service
public class DunningService {

    private final JdbcTemplate jdbc;
    private final TenantJobRunner jobs;
    private final FeeAdjustmentService adjustments;
    private final NotificationService notifications;

    @Value("${schoolsoft.jobs.dunning.enabled:true}")
    private boolean enabled;

    public DunningService(JdbcTemplate jdbc, TenantJobRunner jobs, FeeAdjustmentService adjustments,
                          NotificationService notifications) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.adjustments = adjustments;
        this.notifications = notifications;
    }

    /** Once a day is enough: dunning is measured in days, not minutes. */
    @Scheduled(cron = "${schoolsoft.jobs.dunning.cron:0 30 6 * * *}")
    public void runDaily() {
        if (!enabled) return;
        String runKey = "dunning:" + LocalDate.now();
        jobs.forEachSchool("fee.dunning", runKey, school -> {
            Result result = runFor(school.schoolId(), LocalDate.now());
            return TenantJobRunner.Outcome.of(
                "markedOverdue", result.markedOverdue(),
                "remindersSent", result.remindersSent(),
                "lateFeesApplied", result.lateFeesApplied());
        });
    }

    public record Result(int markedOverdue, int remindersSent, int lateFeesApplied) {}

    /**
     * One school's dunning pass for a given date. Exposed so an operator (and
     * the certification suite) can run it for a chosen day rather than waiting
     * for tomorrow's schedule.
     */
    public Result runFor(UUID schoolId, LocalDate asOf) {
        var policy = jdbc.query(
            "SELECT grace_days, reminder_days, late_fee_pct, late_fee_flat, late_fee_head_id " +
            "FROM dunning_policy WHERE school_id = ? AND is_active",
            (rs, i) -> new Object[]{
                rs.getInt("grace_days"),
                (Integer[]) rs.getArray("reminder_days").getArray(),
                (java.math.BigDecimal) rs.getObject("late_fee_pct"),
                (java.math.BigDecimal) rs.getObject("late_fee_flat"),
                rs.getString("late_fee_head_id") == null ? null : UUID.fromString(rs.getString("late_fee_head_id"))
            },
            schoolId);
        if (policy.isEmpty()) return new Result(0, 0, 0);
        int graceDays = (Integer) policy.get(0)[0];
        Integer[] reminderDays = (Integer[]) policy.get(0)[1];
        java.math.BigDecimal latePct = (java.math.BigDecimal) policy.get(0)[2];
        java.math.BigDecimal lateFlat = (java.math.BigDecimal) policy.get(0)[3];
        UUID lateFeeHeadId = (UUID) policy.get(0)[4];

        List<Overdue> overdue = jdbc.query(
            "SELECT id, student_id, invoice_no, due_on, total, paid, status " +
            "FROM fee_invoice WHERE school_id = ? AND status IN ('open','partial','overdue') " +
            "  AND total > paid AND due_on < ? ORDER BY due_on",
            (rs, i) -> new Overdue(
                UUID.fromString(rs.getString("id")),
                rs.getString("student_id") == null ? null : UUID.fromString(rs.getString("student_id")),
                rs.getString("invoice_no"),
                rs.getDate("due_on").toLocalDate(),
                rs.getDouble("total") - rs.getDouble("paid"),
                rs.getString("status")),
            schoolId, java.sql.Date.valueOf(asOf));

        int marked = 0;
        int reminders = 0;
        int lateFees = 0;
        for (Overdue invoice : overdue) {
            int daysPastDue = (int) java.time.temporal.ChronoUnit.DAYS.between(invoice.dueOn(), asOf);

            if (!"overdue".equals(invoice.status()) && daysPastDue > graceDays
                && recordEvent(schoolId, invoice.id(), "overdue", 0)) {
                jdbc.update("UPDATE fee_invoice SET status = 'overdue', updated_at = now() WHERE id = ?",
                    invoice.id());
                marked++;
            }

            for (Integer offset : reminderDays) {
                if (daysPastDue >= offset && recordEvent(schoolId, invoice.id(), "reminder", offset)) {
                    notifyGuardians(schoolId, invoice, daysPastDue);
                    reminders++;
                }
            }

            boolean lateFeeConfigured = latePct != null || lateFlat != null;
            if (lateFeeConfigured && daysPastDue > graceDays
                && recordEvent(schoolId, invoice.id(), "late_fee", graceDays)) {
                double amount = FeeGenerationService.round(
                    (lateFlat == null ? 0 : lateFlat.doubleValue())
                    + (latePct == null ? 0 : invoice.balance() * latePct.doubleValue() / 100.0));
                if (amount > 0) {
                    adjustments.adjust(schoolId, invoice.id(), "late_fee", amount,
                        "Late fee, " + daysPastDue + " days past due", null, null, lateFeeHeadId);
                    lateFees++;
                }
            }
        }
        return new Result(marked, reminders, lateFees);
    }

    private record Overdue(UUID id, UUID studentId, String invoiceNo, LocalDate dueOn, double balance,
                           String status) {}

    /** False when this exact action was already taken — the idempotency guard. */
    private boolean recordEvent(UUID schoolId, UUID invoiceId, String kind, int dayOffset) {
        return jdbc.update(
            "INSERT INTO dunning_event (id, school_id, fee_invoice_id, kind, day_offset) " +
            "VALUES (?, ?, ?, ?, ?) ON CONFLICT (fee_invoice_id, kind, day_offset) DO NOTHING",
            UUID.randomUUID(), schoolId, invoiceId, kind, dayOffset) > 0;
    }

    private void notifyGuardians(UUID schoolId, Overdue invoice, int daysPastDue) {
        if (invoice.studentId() == null) return;
        List<UUID> guardians = jdbc.query(
            "SELECT guardian_id FROM guardian_student WHERE student_id = ? AND is_communications_recipient",
            (rs, i) -> UUID.fromString(rs.getString("guardian_id")), invoice.studentId());
        for (UUID guardianId : guardians) {
            notifications.notify(schoolId, "guardian", guardianId, "fee_reminder", Map.of(
                "invoiceNo", invoice.invoiceNo(),
                "balance", invoice.balance(),
                "daysPastDue", daysPastDue,
                "dueOn", invoice.dueOn().toString()));
        }
    }
}

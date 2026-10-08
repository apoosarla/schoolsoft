package com.schoolsoft.fees.api;

import com.schoolsoft.fees.internal.AdmissionFeeInvoices;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * How admissions bills an applicant (ADM-13).
 *
 * An applicant is not a student, so none of the student-keyed fee paths can
 * carry their admission fee. This raises an ordinary invoice with the
 * application as its payer — paid, reversed and reported through the same
 * ledger as every other rupee — and hands it to the student when the seat is
 * confirmed. Admissions decides <em>when</em> and <em>how much</em>; nothing
 * here knows what a funnel is.
 */
@Service
public class AdmissionFees {

    private final AdmissionFeeInvoices invoices;

    public AdmissionFees(AdmissionFeeInvoices invoices) {
        this.invoices = invoices;
    }

    /**
     * Raises the application's admission invoice, or returns the one it
     * already has. Asking twice is how a retry, and an application that came
     * back to the stage, are both handled.
     */
    public AdmissionFeeStatusDto raise(UUID schoolId, UUID applicationId, String applicationNo,
                                       String applicantName, double amount) {
        return invoices.raise(schoolId, applicationId, applicationNo, applicantName, amount);
    }

    /** Empty when the application was never billed — its school charges nothing for its grade. */
    public Optional<AdmissionFeeStatusDto> statusFor(UUID applicationId) {
        return invoices.statusFor(applicationId);
    }

    /**
     * Every unpaid admission fee at a school that still belongs to an
     * applicant. Admissions reads it to find the applications that have moved
     * past the fee stage owing — a cheque that came back after the move.
     */
    public List<AdmissionFeeOwedDto> owed(UUID schoolId) {
        return invoices.owed(schoolId);
    }

    /**
     * The application closed. A bill nobody paid anything on is cancelled, so a
     * family that was turned away is not left in the school's dues; one with
     * money on it is left exactly as it is, because whether that money goes
     * back is the school's decision and not a side effect of a rejection.
     */
    public void applicationClosed(UUID applicationId) {
        invoices.cancelIfUnpaid(applicationId);
    }

    /**
     * Pays back everything the family paid on a closed application's fee, one
     * refund per payment so each names the receipt it undoes.
     */
    public AdmissionFeeStatusDto refund(UUID schoolId, UUID applicationId, String reason) {
        return invoices.refund(schoolId, applicationId, reason);
    }

    /** The fee the family paid as applicants goes onto the child's account. */
    public void attachToStudent(UUID applicationId, UUID studentId) {
        invoices.attachToStudent(applicationId, studentId);
    }
}

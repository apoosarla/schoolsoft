package com.schoolsoft.fees.api;

import com.schoolsoft.fees.internal.FeeAdjustmentService;
import com.schoolsoft.fees.internal.FeeChargeRouter;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * How other modules put a charge on a student's account (LIB-03/04, and the
 * transport and conduct charges that follow in later phases).
 *
 * A library fine is a fee, not a library-only number: unless it reaches the
 * ledger and the outstanding-dues report, the school cannot collect it and
 * year-end clearance cannot see it. Callers name the student and the reason;
 * this finds or opens the invoice the charge belongs on.
 */
@Service
public class FeeCharges {

    private final FeeChargeRouter router;
    private final FeeAdjustmentService adjustments;

    public FeeCharges(FeeChargeRouter router, FeeAdjustmentService adjustments) {
        this.router = router;
        this.adjustments = adjustments;
    }

    /**
     * Posts a charge to the student's current open invoice, opening a
     * miscellaneous one if they have none. Returns the adjustment.
     */
    public FeeAdjustmentDto chargeStudent(UUID schoolId, UUID studentId, String feeHeadCode, double amount,
                                          String reason, UUID raisedByStaffId) {
        UUID invoiceId = router.invoiceForCharge(schoolId, studentId, reason);
        UUID feeHeadId = router.feeHeadByCode(schoolId, feeHeadCode);
        return adjustments.adjust(schoolId, invoiceId, "charge", amount, reason, null, raisedByStaffId, feeHeadId);
    }
}

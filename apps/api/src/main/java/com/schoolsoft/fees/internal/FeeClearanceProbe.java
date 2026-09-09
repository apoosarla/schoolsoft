package com.schoolsoft.fees.internal;

import com.schoolsoft.enrolment.api.ClearanceProbe;
import com.schoolsoft.fees.api.FeeDues;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The money line of a leaver's checklist (XFER-08).
 *
 * <p>It asks {@link FeeDues} rather than counting invoices again, which is the
 * same definition of arrears that withholds a report card (ASMT-15) — so a
 * family cannot be blocked at one desk and clear at the other. That definition
 * includes the household's combined invoices, deliberately: a sibling bill is
 * the family's debt, and releasing one child because the invoice happens to name
 * the other is how a school loses the balance entirely.</p>
 */
@Component
public class FeeClearanceProbe implements ClearanceProbe {

    private final FeeDues dues;

    public FeeClearanceProbe(FeeDues dues) { this.dues = dues; }

    @Override
    public String area() { return "fees"; }

    @Override
    public Finding probe(UUID studentId, LocalDate lastWorkingDate) {
        double outstanding = dues.outstandingForStudent(studentId);
        if (outstanding <= 0) return Finding.clear("Nothing outstanding");
        return Finding.blocked(String.format("%.2f outstanding across live invoices", outstanding), outstanding);
    }
}

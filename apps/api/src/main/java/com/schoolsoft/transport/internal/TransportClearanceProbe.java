package com.schoolsoft.transport.internal;

import com.schoolsoft.enrolment.api.ClearanceProbe;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bus line of a leaver's checklist, and the thing that empties the seat
 * (TRN-09).
 *
 * <p>Transport never blocks an exit — there is nothing for the family to settle,
 * only something for the school to stop doing. So it reports the assignment and
 * then {@link #settle} closes it on the last working day, which is what takes
 * the child off the route roster and off the bill for the seat. Leaving that to
 * the transport office to remember is how a school ends up with a driver
 * checking in for a child who left in August.</p>
 */
@Component
public class TransportClearanceProbe implements ClearanceProbe {

    private final TransportRepository repo;

    public TransportClearanceProbe(TransportRepository repo) { this.repo = repo; }

    @Override
    public String area() { return "transport"; }

    @Override
    public Finding probe(UUID studentId, LocalDate lastWorkingDate) {
        var assignment = repo.activeAssignmentOn(studentId, lastWorkingDate);
        return assignment
            .map(route -> Finding.clear("Riding " + route + "; the seat is released on the last working day"))
            .orElseGet(() -> Finding.clear("No route assigned"));
    }

    @Override
    public void settle(UUID studentId, LocalDate lastWorkingDate) {
        repo.endAssignment(studentId, lastWorkingDate);
    }
}

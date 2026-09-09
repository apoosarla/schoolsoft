package com.schoolsoft.library.internal;

import com.schoolsoft.enrolment.api.ClearanceProbe;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The library line of a leaver's checklist (LIB-05).
 *
 * <p>An unreturned copy blocks the exit; the fine on it does not, because a fine
 * is money and money is the fees line's business — it reaches
 * {@code FeeCharges} the moment the copy is returned or written off, and shows
 * up on the same checklist one row above. Counting it twice would let a school
 * clear the fees line by paying and still be blocked by a number that was only
 * ever a shadow of it.</p>
 */
@Component
public class LibraryClearanceProbe implements ClearanceProbe {

    private final LibraryRepository repo;

    public LibraryClearanceProbe(LibraryRepository repo) { this.repo = repo; }

    @Override
    public String area() { return "library"; }

    @Override
    public Finding probe(UUID studentId, LocalDate lastWorkingDate) {
        var outstanding = repo.listActiveForMember("student", studentId);
        if (outstanding.isEmpty()) return Finding.clear("No copies out");
        return Finding.blocked(outstanding.size() == 1
            ? "1 copy not returned"
            : outstanding.size() + " copies not returned", null);
    }
}

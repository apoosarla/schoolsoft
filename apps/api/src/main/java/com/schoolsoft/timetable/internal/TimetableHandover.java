package com.schoolsoft.timetable.internal;

import com.schoolsoft.people.api.StaffDutyHandover;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.timetable.api.TimetableSlotDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The timetable's answer to a member of staff leaving: the periods they are
 * still down to teach after their last day, and the act of giving those to
 * somebody else.
 */
@Service
public class TimetableHandover implements StaffDutyHandover {

    private final TimetableRepository repo;

    public TimetableHandover(TimetableRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<Duty> heldAfter(UUID staffId, LocalDate lastDay) {
        return repo.slotsHeldAfter(staffId, lastDay).stream()
            .map(slot -> new Duty("timetable", repo.describe(slot)))
            .toList();
    }

    @Override
    public void handOver(UUID staffId, UUID successorStaffId, LocalDate lastDay) {
        for (TimetableSlotDto slot : repo.slotsHeldAfter(staffId, lastDay)) {
            try {
                repo.handOverSlot(slot, successorStaffId, lastDay);
            } catch (IllegalArgumentException clash) {
                // The successor already teaches somewhere else at that time.
                // Named, because "pick somebody else" is only actionable if the
                // office can see which period is the problem.
                throw new ConflictException(
                    "The successor cannot take " + repo.describe(slot) + ": they are already timetabled then. "
                        + "Free that period or name somebody else.");
            }
        }
    }
}

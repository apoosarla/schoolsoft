package com.schoolsoft.tenancy.internal;

import com.schoolsoft.iam.api.StaffTenure;
import com.schoolsoft.people.api.StaffDutyHandover;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.SectionSubjectTeacherDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who teaches what in a section — the standing assignment, as distinct from a
 * timetabled period.
 *
 * <p>The assignment used to be written by the controller straight into the
 * repository, which took any staff id at all: the bursar could be made class
 * teacher of 10-A, and then held a section in every read that derives a
 * teacher's reach from this table. Only somebody who holds a teaching grant
 * and is still on the books can be given one now (STF-01).</p>
 */
@Service
public class TeacherAssignmentService implements StaffDutyHandover {

    private final SchoolRepository repo;
    private final StaffTenure tenure;
    private final SchoolClock clock;

    public TeacherAssignmentService(SchoolRepository repo, StaffTenure tenure, SchoolClock clock) {
        this.repo = repo;
        this.tenure = tenure;
        this.clock = clock;
    }

    public List<SectionSubjectTeacherDto> forSection(UUID sectionId) {
        UUID schoolId = repo.schoolOfSection(sectionId).orElse(null);
        return repo.listSectionSubjectTeachers(sectionId, clock.today(schoolId));
    }

    @Transactional
    public SectionSubjectTeacherDto assign(UUID sectionId, UUID subjectId, UUID teacherStaffId,
                                           boolean isPrimary, boolean isElective) {
        UUID schoolId = repo.schoolOfSection(sectionId)
            .orElseThrow(() -> new NotFoundException("Section not found: " + sectionId));
        if (!tenure.isOnBooks(teacherStaffId, clock.today(schoolId))) {
            throw new ConflictException("That member of staff is not on the school's books");
        }
        if (!tenure.canTeach(teacherStaffId)) {
            throw new ConflictException(
                "That member of staff holds no teaching role. Give them one on the Roles screen "
                    + "before assigning them a section.");
        }
        return repo.assignSectionSubjectTeacher(sectionId, subjectId, teacherStaffId, isPrimary, isElective);
    }

    // ---- StaffDutyHandover ----

    @Override
    public List<Duty> heldAfter(UUID staffId, LocalDate lastDay) {
        return repo.assignmentsHeldAfter(staffId, lastDay).stream()
            .map(a -> new Duty("teaching",
                (a.isPrimary() ? "Class teacher and " : "") + a.subjectName()
                    + " teacher of " + repo.sectionLabel(a.sectionId())))
            .toList();
    }

    @Override
    public void handOver(UUID staffId, UUID successorStaffId, LocalDate lastDay) {
        for (SectionSubjectTeacherDto held : repo.assignmentsHeldAfter(staffId, lastDay)) {
            repo.handOverAssignment(held, successorStaffId, lastDay);
        }
    }
}

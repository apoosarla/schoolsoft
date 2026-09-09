package com.schoolsoft.admissions.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.api.PublicAdmissions;
import com.schoolsoft.notification.api.Notice;
import com.schoolsoft.notification.api.NotificationService;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The admissions use cases that owe somebody outside the school an answer.
 *
 * <p>A family that submits an enquiry has no account, no student record and
 * nothing to log in to — the acknowledgement is the only evidence they have
 * that the form worked (ADM-01), and the outcome notification is the only way
 * they learn the decision (ADM-14). Both are addressed to the applicant, which
 * is the application itself: the contact details on the form are all the
 * school holds until conversion creates a guardian.</p>
 *
 * <p>This sits between the controller and {@link AdmissionsRepository} rather
 * than inside it because sending is a use-case step, not a SQL one, and
 * because {@code publicsite} reaches the same two operations through
 * {@link PublicAdmissions} — the acknowledgement has to happen on both paths
 * or the website form is the one that silently doesn't.</p>
 */
@Service
public class AdmissionsService implements PublicAdmissions {

    /**
     * The states a family is waiting on. Every other transition is internal
     * pipeline movement that would only be noise on a parent's phone.
     */
    private static final Map<String, String> OUTCOME_TEMPLATES = Map.of(
        "offered", "admission_offered",
        "rejected", "admission_rejected",
        "enrolled", "admission_enrolled",
        "lapsed", "admission_lapsed"
    );

    private final AdmissionsRepository repo;
    private final NotificationService notifications;

    public AdmissionsService(AdmissionsRepository repo, NotificationService notifications) {
        this.repo = repo;
        this.notifications = notifications;
    }

    @Override
    public Optional<AdmissionApplicationDto> findByApplicationNoAndPhone(String applicationNo, String guardianPhone) {
        return repo.findByApplicationNoAndPhone(applicationNo, guardianPhone);
    }

    @Override
    public AdmissionApplicationDto create(
        UUID schoolId, UUID academicYearId, UUID gradeId, String applicationNo,
        String firstName, String lastName, LocalDate dob, String gender,
        String guardianName, String guardianPhone, String guardianEmail, String source
    ) {
        AdmissionApplicationDto created = repo.create(
            schoolId, academicYearId, gradeId, applicationNo, firstName, lastName, dob, gender,
            guardianName, guardianPhone, guardianEmail, source);

        notifications.notify(
            Notice.of(schoolId, "admission_received", Map.of(
                    "applicationNo", created.applicationNo(),
                    "applicantName", displayName(firstName, lastName),
                    "guardianName", guardianName,
                    "source", source == null ? "walkin" : source))
                .about("admission", created.id())
                .oncePer("admission:received:" + created.id()),
            "applicant", created.id());

        return created;
    }

    public AdmissionApplicationDto transition(UUID id, String toState, UUID actorUserId) {
        AdmissionApplicationDto moved = repo.transition(id, toState, actorUserId);

        String template = OUTCOME_TEMPLATES.get(toState);
        if (template != null) {
            notifications.notify(
                Notice.of(moved.schoolId(), template, Map.of(
                        "applicationNo", moved.applicationNo(),
                        "applicantName", displayName(moved.applicantFirstName(), moved.applicantLastName()),
                        "state", toState))
                    .about("admission", id)
                    // Re-running a transition that already happened is not an
                    // error, so it must not be a second message either.
                    .oncePer("admission:" + toState + ":" + id),
                "applicant", id);
        }
        return moved;
    }

    private static String displayName(String first, String last) {
        return (first + " " + (last == null ? "" : last)).trim();
    }
}

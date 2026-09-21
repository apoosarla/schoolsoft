package com.schoolsoft.admissions.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.api.AdmissionPolicyDto;
import com.schoolsoft.admissions.api.PublicAdmissions;
import com.schoolsoft.iam.api.PermissionChecker;
import com.schoolsoft.notification.api.Notice;
import com.schoolsoft.notification.api.NotificationService;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PermissionChecker permissions;

    public AdmissionsService(AdmissionsRepository repo, NotificationService notifications,
                             PermissionChecker permissions) {
        this.repo = repo;
        this.notifications = notifications;
        this.permissions = permissions;
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

    /**
     * Moves an application through the funnel, and refuses a move the funnel
     * does not have.
     *
     * <p>The legal moves are rows in {@code admission_transition}, not a switch
     * here. Four things have to hold, in this order — the move exists, it
     * belongs to the funnel <em>this school</em> runs (a school with no
     * entrance test has no test to schedule), the caller holds the permission
     * <em>that move</em> needs on top of the endpoint's
     * {@code admission.decide} (which is why a counsellor can decide but not
     * enrol), and the application is still where the caller last read it.</p>
     *
     * <p>Re-running a move that already happened is not an error. A retried
     * request must not fail because the first attempt worked, so a call whose
     * target is already the current state returns it and sends nothing — the
     * {@code oncePer} key would have suppressed a second message anyway, but
     * not writing a second {@code admission_event} row matters to the
     * time-in-stage figures that trail feeds.</p>
     */
    @Transactional
    public AdmissionApplicationDto transition(UUID id, String toState, UUID actorUserId,
                                              LocalDate offerExpiresOn) {
        var current = repo.find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
        if (toState.equals(current.state())) return current;

        var policy = repo.policy(current.schoolId());
        var move = repo.move(current.state(), toState)
            .filter(m -> m.fitsFunnel(policy.entranceTestRequired()))
            .orElseThrow(() -> new ConflictException(refusal(current.state(), toState, policy)));

        if (!permissions.can(move.requiresPerm())) {
            throw new ForbiddenException(
                "Moving an application from '" + current.state() + "' to '" + toState + "' needs "
                + move.requiresPerm() + ".");
        }

        // An offer that carries no expiry is one nothing can ever lapse, and
        // the family is shown no deadline. The date is the school's offer
        // window from today, unless the caller names one — which is how an
        // extension is granted.
        LocalDate expiry = null;
        if ("offered".equals(toState)) {
            expiry = offerExpiresOn != null ? offerExpiresOn
                : LocalDate.now().plusDays(policy.offerValidityDays());
        }

        if (!repo.transitionFrom(id, current.state(), toState, actorUserId, expiry)) {
            var now = repo.find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
            // Two people made the same move at once. The second one has nothing
            // to complain about — the application is where they wanted it.
            if (toState.equals(now.state())) return now;
            throw new ConflictException(
                "Application " + id + " moved to '" + now.state() + "' while this move was in flight; "
                + "it is no longer in '" + current.state() + "'.");
        }
        AdmissionApplicationDto moved = repo.find(id).orElseThrow();

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

    /**
     * Says what is possible from here, in this school's funnel — and, when the
     * move exists but belongs to the other funnel, says that instead of
     * pretending the move was never defined. "Not a thing" and "not a thing
     * *here*" send the office to two different places.
     */
    private String refusal(String fromState, String toState, AdmissionPolicyDto policy) {
        String allowed = String.join(", ", repo.movesFrom(fromState, policy.entranceTestRequired()));
        if (repo.move(fromState, toState).isPresent()) {
            return "An application in '" + fromState + "' cannot move to '" + toState + "' at this school: "
                + (policy.entranceTestRequired()
                    ? "the school runs an entrance test, so an offer follows the test rather than the review."
                    : "the school runs no entrance test, so there is no test to schedule.")
                + " From here the funnel allows: " + allowed + ".";
        }
        return "An application in '" + fromState + "' cannot move to '" + toState + "'. From here the funnel "
            + "allows: " + allowed + ".";
    }

    /**
     * Records the entrance-test result. Refused outright at a school that runs
     * no entrance test — a score there is a number about an event that never
     * happened, and the funnel has no {@code test_scheduled} state to be in.
     */
    @Transactional
    public AdmissionApplicationDto recordTestScore(UUID id, double score, String notes, UUID actorUserId) {
        var current = repo.find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
        if (!repo.policy(current.schoolId()).entranceTestRequired()) {
            throw new ConflictException(
                "This school runs no entrance test, so there is no score to record. Turn the entrance test on "
                + "in the admissions settings if that has changed.");
        }
        repo.recordTestScore(id, score, notes, actorUserId);
        return repo.find(id).orElseThrow();
    }

    public AdmissionPolicyDto policy(UUID schoolId) {
        return repo.policy(schoolId);
    }

    public AdmissionPolicyDto savePolicy(UUID schoolId, boolean entranceTestRequired, int offerValidityDays) {
        if (offerValidityDays < 1) {
            throw new IllegalArgumentException("An offer has to stand for at least a day.");
        }
        return repo.savePolicy(schoolId, entranceTestRequired, offerValidityDays);
    }

    /**
     * Confirms a seat: the application becomes a student, an enrolment and a
     * guardian link, and lands on {@code enrolled}.
     *
     * <p>The transaction lives here because the use case does. Conversion
     * writes a student, an enrolment, a guardian, a login and the application's
     * own state, and a refusal partway — a full section, an application that
     * left {@code accepted} — must leave none of them behind (ADM-10).</p>
     */
    @Transactional
    public UUID enrol(UUID applicationId, UUID sectionId, String rollNo, String overCapacityReason) {
        return repo.convertToStudent(applicationId, sectionId, rollNo, overCapacityReason);
    }

    private static String displayName(String first, String last) {
        return (first + " " + (last == null ? "" : last)).trim();
    }
}

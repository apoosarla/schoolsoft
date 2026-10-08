package com.schoolsoft.people.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.iam.api.AccountProvisioning;
import com.schoolsoft.iam.api.PermissionChecker;
import com.schoolsoft.iam.api.StaffTenure;
import com.schoolsoft.people.api.StaffDto;
import com.schoolsoft.people.api.StaffDutyHandover;
import com.schoolsoft.people.api.StaffDutyHandover.Duty;
import com.schoolsoft.people.api.StaffOnboarding;
import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member of staff from the day the office puts them on the books to the day
 * they leave (GAP-44, STF-01, STF-04).
 *
 * <h2>Joining</h2>
 * One act creates the record, the sign-in account and the role grants, because
 * each without the others is a person who exists on paper and cannot do their
 * job: no account and they cannot sign in, no role and they land on an empty
 * dashboard. Granting roles while hiring needs {@code role.manage} as well as
 * {@code staff.manage} — this must not be a second, quieter way to hand
 * somebody the principal's keys.
 *
 * <h2>Leaving</h2>
 * An exit names a last working day and is refused while the person would still
 * hold work after it. The modules that hang work off a staff id say what that
 * is and move it ({@link StaffDutyHandover}); this class only insists that
 * somebody is named. What the leaver did before they went — marks, registers,
 * lesson plans — carries their id and is not touched. Access is not revoked by
 * anything here either: {@code left_on} is the authority, and
 * {@link StaffTenure} is where sign-in and permission resolution read it.
 */
@Service
public class StaffService {

    private static final Set<String> EMPLOYMENT_TYPES = Set.of("permanent", "contract", "temp", "visiting");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");
    private static final String EMPLOYEE_PATTERN = "EMP{SEQ:4}";

    private final PeopleRepository repo;
    private final AccountProvisioning accounts;
    private final PermissionChecker perm;
    private final StaffTenure tenure;
    private final NumberSeries numbers;
    private final SchoolClock clock;
    private final AuditService audit;
    private final List<StaffDutyHandover> handovers;

    public StaffService(PeopleRepository repo, AccountProvisioning accounts, PermissionChecker perm,
                        StaffTenure tenure, NumberSeries numbers, SchoolClock clock, AuditService audit,
                        List<StaffDutyHandover> handovers) {
        this.repo = repo;
        this.accounts = accounts;
        this.perm = perm;
        this.tenure = tenure;
        this.numbers = numbers;
        this.clock = clock;
        this.audit = audit;
        this.handovers = handovers;
    }

    public record NewStaff(
        UUID schoolId, UUID campusId, String employeeNo, String firstName, String lastName,
        String email, String phone, String employmentType, LocalDate joinedOn, List<String> roleCodes
    ) {}

    @Transactional
    public StaffDto create(NewStaff req) {
        String firstName = required(req.firstName(), "First name");
        String email = email(req.email());
        String phone = phone(req.phone());
        requireAWayIn(email, phone);
        String employmentType = employmentType(req.employmentType());

        List<String> roles = req.roleCodes() == null ? List.of() : req.roleCodes().stream().distinct().toList();
        if (!roles.isEmpty() && !perm.can(Perm.ROLE_MANAGE.code())) {
            throw new ForbiddenException(
                "You can add staff but not grant roles. Add them without a role and ask somebody who "
                    + "manages roles to grant one.");
        }
        for (String role : roles) {
            if (!accounts.roleExists(role)) throw new IllegalArgumentException("There is no role called " + role);
        }
        if (accounts.identityTaken(null, email, phone)) {
            throw new ConflictException("Somebody already signs in with that email address or mobile number");
        }

        UUID campusId = req.campusId() != null ? req.campusId()
            : repo.primaryCampusOf(req.schoolId()).orElseThrow(() ->
                new ConflictException("This school has no campus yet. Add one before adding staff."));

        StaffDto created = repo.createStaff(new StaffOnboarding.NewStaff(
            req.schoolId(), campusId, employeeNo(req.schoolId(), req.employeeNo()),
            firstName, blankToNull(req.lastName()), email, phone, employmentType,
            req.joinedOn() == null ? clock.today(req.schoolId()) : req.joinedOn()));

        accounts.createStaffAccount(req.schoolId(), created.id(), email, phone);
        for (String role : roles) accounts.grantSchoolRole(created.id(), req.schoolId(), role);

        // A map of strings rather than the record: the audit writer's mapper
        // does not carry the date module, and the roles are part of what was done.
        audit.record("staff.created", "staff", created.id(), null,
            Map.of("employeeNo", created.employeeNo(), "firstName", created.firstName(),
                   "employmentType", created.employmentType(), "joinedOn", String.valueOf(created.joinedOn()),
                   "roleCodes", roles),
            null, null);
        return created;
    }

    public record StaffEdit(
        String firstName, String lastName, String email, String phone,
        String employmentType, LocalDate joinedOn, UUID campusId, int version
    ) {}

    @Transactional
    public StaffDto update(UUID id, StaffEdit req) {
        StaffDto current = find(id);
        String firstName = required(req.firstName(), "First name");
        String email = email(req.email());
        String phone = phone(req.phone());
        requireAWayIn(email, phone);
        if (current.leftOn() != null && req.joinedOn() != null && req.joinedOn().isAfter(current.leftOn())) {
            throw new IllegalArgumentException("The joining date cannot be after the last working day");
        }
        if (accounts.identityTaken(id, email, phone)) {
            throw new ConflictException("Somebody else already signs in with that email address or mobile number");
        }
        boolean saved = repo.updateStaff(id, firstName, blankToNull(req.lastName()), email, phone,
            employmentType(req.employmentType()), req.joinedOn(),
            req.campusId() == null ? current.campusId() : req.campusId(), req.version());
        if (!saved) {
            throw new ConflictException("Somebody else changed this record while you had it open. Reload and try again.");
        }
        accounts.syncStaffIdentity(current.schoolId(), id, email, phone);
        return find(id);
    }

    /** What the person would still hold the day after {@code lastWorkingDate}. */
    public List<Duty> duties(UUID id, LocalDate lastWorkingDate) {
        StaffDto staff = find(id);
        return dutiesAfter(id, handoverDay(staff, lastWorkingDate));
    }

    @Transactional
    public StaffDto exit(UUID id, LocalDate lastWorkingDate, String reason, UUID successorStaffId, int version) {
        StaffDto staff = find(id);
        if (staff.leftOn() != null) {
            // A retry of an exit that already went through is not a failure.
            if (staff.leftOn().equals(lastWorkingDate)) return staff;
            throw new ConflictException(
                staff.firstName() + " is already recorded as leaving on " + staff.leftOn());
        }
        if (staff.joinedOn() != null && lastWorkingDate.isBefore(staff.joinedOn())) {
            throw new IllegalArgumentException(
                "The last working day cannot be before the day they joined (" + staff.joinedOn() + ")");
        }

        LocalDate handoverDay = handoverDay(staff, lastWorkingDate);
        List<Duty> held = dutiesAfter(id, handoverDay);
        UUID successor = held.isEmpty() ? null : successorStaffId;
        if (!held.isEmpty()) {
            if (successorStaffId == null) {
                throw new ConflictException(
                    staff.firstName() + " still holds " + held.size() + (held.size() == 1 ? " duty" : " duties")
                        + " after " + lastWorkingDate + " — " + summary(held)
                        + ". Name who takes them over.");
            }
            requireFitSuccessor(staff, successorStaffId, handoverDay);
            for (StaffDutyHandover handover : handovers) handover.handOver(id, successorStaffId, handoverDay);
        }

        if (!repo.recordStaffExit(id, lastWorkingDate, reason.trim(), successor, version)) {
            throw new ConflictException("Somebody else changed this record while you had it open. Reload and try again.");
        }
        return find(id);
    }

    // ------------------------------------------------------------- helpers

    private StaffDto find(UUID id) {
        return repo.findStaff(id).orElseThrow(() -> new NotFoundException("Staff member not found: " + id));
    }

    /**
     * The last day the leaver's duties are theirs. Normally the last working
     * day itself; for an exit filed after the fact — somebody who stopped
     * coming in last week — it is yesterday, because the days since have
     * already been taught and a handover must not rewrite who taught them.
     */
    private LocalDate handoverDay(StaffDto staff, LocalDate lastWorkingDate) {
        LocalDate yesterday = clock.today(staff.schoolId()).minusDays(1);
        return lastWorkingDate.isBefore(yesterday) ? yesterday : lastWorkingDate;
    }

    private List<Duty> dutiesAfter(UUID id, LocalDate day) {
        return handovers.stream().flatMap(h -> h.heldAfter(id, day).stream()).toList();
    }

    private void requireFitSuccessor(StaffDto leaver, UUID successorId, LocalDate handoverDay) {
        if (successorId.equals(leaver.id())) {
            throw new IllegalArgumentException("Somebody cannot hand their duties over to themselves");
        }
        StaffDto successor = repo.findStaff(successorId)
            .filter(s -> s.schoolId().equals(leaver.schoolId()))
            .orElseThrow(() -> new NotFoundException("Successor not found at this school: " + successorId));
        if (!tenure.isOnBooks(successorId, handoverDay.plusDays(1))) {
            throw new ConflictException(
                successor.firstName() + " will have left by then too, and cannot take these duties over");
        }
        if (!tenure.canTeach(successorId)) {
            throw new ConflictException(
                successor.firstName() + " holds no teaching role, and what is being handed over is teaching");
        }
    }

    private static String summary(List<Duty> held) {
        String shown = String.join("; ", held.stream().limit(4).map(Duty::description).toList());
        return held.size() > 4 ? shown + "; and " + (held.size() - 4) + " more" : shown;
    }

    /** The number the office typed, or the next one in the school's series. */
    private String employeeNo(UUID schoolId, String given) {
        String typed = blankToNull(given);
        if (typed != null) {
            if (repo.employeeNoTaken(schoolId, typed)) {
                throw new ConflictException("Employee number " + typed + " is already in use");
            }
            return typed;
        }
        // The series starts past a count of existing rows, which is not the same
        // as past every number somebody typed by hand; step over any it meets.
        for (int attempt = 0; attempt < 50; attempt++) {
            String next = numbers.next(schoolId, NumberSeries.Kind.employee, null, EMPLOYEE_PATTERN, null);
            if (!repo.employeeNoTaken(schoolId, next)) return next;
        }
        throw new ConflictException("Could not issue an employee number; enter one by hand");
    }

    private static void requireAWayIn(String email, String phone) {
        if (email == null && phone == null) {
            throw new IllegalArgumentException("Enter an email address or a mobile number — it is how they sign in");
        }
    }

    private static String required(String value, String label) {
        String v = blankToNull(value);
        if (v == null) throw new IllegalArgumentException(label + " is required");
        return v;
    }

    private static String email(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        v = v.toLowerCase(java.util.Locale.ROOT);
        if (!EMAIL.matcher(v).matches()) throw new IllegalArgumentException("That email address does not look right");
        return v;
    }

    private static String phone(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        v = v.replaceAll("[\\s-]", "");
        if (!PHONE.matcher(v).matches()) {
            throw new IllegalArgumentException("Enter the mobile number as digits, with the country code if it has one");
        }
        return v;
    }

    private static String employmentType(String value) {
        String v = blankToNull(value);
        if (v == null) return "permanent";
        if (!EMPLOYMENT_TYPES.contains(v)) {
            throw new IllegalArgumentException("Employment type must be one of permanent, contract, temp or visiting");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

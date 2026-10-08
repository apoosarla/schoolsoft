package com.schoolsoft.privacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.privacy.api.ConsentDto;
import com.schoolsoft.privacy.api.DataRequestDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyService {

    private static final Pattern PURPOSE = Pattern.compile("^[a-z][a-z0-9_]{1,40}$");

    private final PrivacyRepository repo;
    private final SubjectExport export;
    private final Erasure erasure;
    private final SchoolClock clock;
    private final int windowDays;

    public PrivacyService(PrivacyRepository repo, SubjectExport export, Erasure erasure, SchoolClock clock,
                          @Value("${schoolsoft.privacy.request-window-days:30}") int windowDays) {
        this.repo = repo;
        this.export = export;
        this.erasure = erasure;
        this.clock = clock;
        this.windowDays = windowDays;
    }

    // ---------------------------------------------------------------- consent

    public List<ConsentDto> consents(UUID studentId) {
        schoolOf(studentId);
        return repo.consents(studentId);
    }

    /**
     * Records a yes, or takes one back. Both are safe to repeat: granting what
     * already stands and withdrawing what was never given change nothing and
     * are not errors, because the family's app retries.
     */
    @Transactional
    public List<ConsentDto> setConsent(UUID studentId, String purpose, boolean granted, String source) {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("A consent names its purpose in lower_snake_case");
        }
        UUID schoolId = schoolOf(studentId);
        if (granted) {
            repo.grant(schoolId, studentId, purpose, source, caller());
        } else {
            repo.withdraw(studentId, purpose);
        }
        return repo.consents(studentId);
    }

    // --------------------------------------------------------------- requests

    /**
     * Files a request and starts its clock.
     *
     * <p>An access request is served by being asked: the caller has already
     * been shown to be this child's parent or the office, and there is nothing
     * left for anybody to decide, so making a family wait on a queue for a
     * copy of their own data would be delay for its own sake. An erasure waits
     * for the office, because it cannot be taken back.</p>
     */
    @Transactional
    public DataRequestDto file(UUID studentId, String kind, String note) {
        if (!"access".equals(kind) && !"erasure".equals(kind)) {
            throw new IllegalArgumentException("A data request is an 'access' or an 'erasure'");
        }
        UUID schoolId = schoolOf(studentId);
        LocalDate today = clock.today(schoolId);
        UUID id = repo.file(schoolId, studentId, kind, "access".equals(kind) ? "fulfilled" : "open",
            caller(), note, today, today.plusDays(windowDays));
        return request(id);
    }

    public DataRequestDto request(UUID id) {
        return repo.request(id, clock.today())
            .orElseThrow(() -> new NotFoundException("Data request not found: " + id));
    }

    /** Oldest deadline first: the top of the list is what the office owes soonest. */
    public List<DataRequestDto> requests(String status) {
        return repo.requests(status, clock.today());
    }

    /** The copy an access request asked for. Not available on an erasure, or on a refusal. */
    public JsonNode exportFor(UUID requestId) {
        DataRequestDto request = request(requestId);
        if (!"access".equals(request.kind()) || !"fulfilled".equals(request.status())) {
            throw new ConflictException("This request has no export: it is " + request.status()
                + " and asks for " + request.kind());
        }
        return export.of(request.studentId());
    }

    /**
     * Serves an erasure. Refused while the child has an enrolment that has not
     * ended — a school cannot keep a register of a child it may not know — and
     * the request stays open, so the same request can be served the day after
     * they leave.
     *
     * <p>Serving it twice is not an error: the retry gets the request as the
     * first attempt left it.</p>
     */
    @Transactional
    public DataRequestDto fulfil(UUID id, String reason) {
        DataRequestDto request = request(id);
        if ("fulfilled".equals(request.status())) return request;
        if ("refused".equals(request.status())) {
            throw new ConflictException("This request was refused; a new one has to be filed");
        }
        if (repo.stillEnrolled(request.studentId(), clock.today(request.schoolId()))) {
            throw new ConflictException("This child is still on the school's register. Their details can "
                + "be erased once their enrolment has ended.");
        }
        if (!repo.decide(id, "fulfilled", caller(), reason)) {
            return settled(id);
        }
        repo.withdrawAll(request.studentId());
        erasure.erase(request.studentId());
        return request(id);
    }

    @Transactional
    public DataRequestDto refuse(UUID id, String reason) {
        DataRequestDto request = request(id);
        if ("refused".equals(request.status())) return request;
        if (!repo.decide(id, "refused", caller(), reason)) {
            DataRequestDto now = request(id);
            if ("refused".equals(now.status())) return now;
            throw new ConflictException("This request has already been served");
        }
        return request(id);
    }

    /** Somebody decided between our read and our write; answer with what they decided. */
    private DataRequestDto settled(UUID id) {
        DataRequestDto now = request(id);
        if ("fulfilled".equals(now.status())) return now;
        throw new ConflictException("This request was refused; a new one has to be filed");
    }

    private UUID schoolOf(UUID studentId) {
        return repo.schoolOfStudent(studentId)
            .orElseThrow(() -> new NotFoundException("Student not found: " + studentId));
    }

    private static UUID caller() {
        var snap = TenantContext.get();
        return snap == null ? null : snap.userAccountId();
    }
}

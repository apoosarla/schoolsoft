-- Permission grants: which role may do what.
--
-- Until now `role.screen_keys` was the only thing standing between a caller
-- and an endpoint, and it stood nowhere near one — it drives admin-web's
-- navigation and nothing else, so any authenticated token could call any
-- endpoint, including `POST /v1/iam/staff-roles/assign`. This table is the
-- grant side of the fix; the vocabulary side is
-- `platform/security/Perm.java`, and `@PreAuthorize("@perm.can('...')")` on
-- every controller method is where the two meet.
--
-- The vocabulary is code and the grants are data on purpose: a school can
-- define a custom role and hand it permissions without a deploy, but it
-- cannot invent a permission, because a permission only means something if
-- an endpoint checks it. `RbacArchitectureTest` fails the build on a code
-- here that no longer exists in the enum.
--
-- Scope (campus, section, subject) is a separate axis and stays where it is,
-- in `staff_role.scope_type` and the per-module authorizers. A permission
-- says "may you use this endpoint at all", not "for whom".

CREATE TABLE IF NOT EXISTS role_perm (
    role_code   TEXT NOT NULL REFERENCES role(code) ON DELETE CASCADE,
    perm_code   TEXT NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_code, perm_code)
);

CREATE INDEX IF NOT EXISTS role_perm_perm_idx ON role_perm(perm_code);

-- The driver persona. driver-app has been shipping against a login with no
-- role at all; it needs one now that the endpoints are gated.
INSERT INTO role (code, name, description, screen_keys, is_system) VALUES
('driver', 'Driver', 'Drives a school route: runs trips, checks students on and off, reports position.',
    ARRAY['dashboard'], TRUE)
ON CONFLICT (code) DO NOTHING;

-- ===== the heads: everything =====
-- Kept as an explicit list rather than a wildcard so that adding a permission
-- is a decision about who gets it, not an automatic grant to the top three.
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, p.perm
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin')) AS r(code)
CROSS JOIN (VALUES
    ('structure.view'), ('structure.manage'), ('academic_year.manage'), ('teacher.assign'),
    ('curriculum.view'), ('curriculum.manage'),
    ('student.view'), ('student.manage'), ('guardian.view'), ('staff.view'), ('directory.view'),
    ('enrolment.view'), ('enrolment.manage'), ('election.manage'),
    ('admission.view'), ('admission.manage'), ('admission.decide'), ('admission.enrol'),
    ('calendar.view'), ('calendar.manage'), ('closure.declare'),
    ('attendance.view'), ('attendance.mark'), ('attendance.amend.request'),
    ('attendance.amend.decide'), ('attendance.policy.manage'),
    ('leave.view'), ('leave.apply'), ('leave.decide'),
    ('timetable.view'), ('timetable.manage'), ('cover.view'), ('cover.manage'),
    ('assessment.view'), ('assessment.manage'), ('assessment.policy.manage'),
    ('mark.view'), ('mark.enter'), ('mark.reeval.request'), ('mark.reeval.decide'),
    ('report_card.view'), ('report_card.generate'), ('report_card.lock'), ('report_card.publish'),
    ('exam.view'), ('exam.manage'), ('exam.publish'), ('hall_ticket.issue'), ('hall_ticket.view'),
    ('fee.structure.view'), ('fee.structure.manage'), ('fee.invoice.view'), ('fee.invoice.manage'),
    ('fee.payment.record'), ('fee.adjustment.manage'), ('fee.concession.manage'),
    ('fee.report.view'), ('dunning.manage'),
    ('lms.content.view'), ('lms.content.manage'), ('lms.grade'),
    ('library.view'), ('library.manage'), ('library.circulate'),
    ('announcement.view'), ('announcement.manage'), ('message.participate'),
    ('transport.view'), ('transport.manage'), ('transport.track'), ('transport.drive'),
    ('device.view'), ('device.manage'), ('device.event.post'),
    ('rollover.view'), ('rollover.manage'),
    ('board_export.view'), ('board_export.manage'),
    ('role.view'), ('role.manage'), ('audit.view'),
    ('theme.view'), ('theme.manage'), ('feature_flag.view'), ('feature_flag.manage'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== academic coordinator: the whole academic pipeline, none of the money =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'academic_coordinator', p.perm FROM (VALUES
    ('structure.view'), ('teacher.assign'), ('curriculum.view'), ('curriculum.manage'),
    ('student.view'), ('staff.view'), ('directory.view'), ('enrolment.view'),
    ('calendar.view'),
    ('attendance.view'), ('attendance.amend.decide'), ('leave.view'), ('leave.decide'),
    ('timetable.view'), ('timetable.manage'), ('cover.view'), ('cover.manage'),
    ('assessment.view'), ('assessment.manage'), ('assessment.policy.manage'),
    ('mark.view'), ('mark.reeval.decide'),
    ('report_card.view'), ('report_card.generate'), ('report_card.lock'), ('report_card.publish'),
    ('exam.view'), ('exam.manage'), ('exam.publish'), ('hall_ticket.view'),
    ('lms.content.view'), ('lms.content.manage'), ('lms.grade'),
    ('announcement.view'), ('announcement.manage'), ('message.participate'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== exams officer: the assessment cycle end to end =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'exams_officer', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('directory.view'), ('calendar.view'),
    ('assessment.view'), ('assessment.manage'),
    ('mark.view'), ('mark.enter'), ('mark.reeval.decide'),
    ('report_card.view'), ('report_card.generate'), ('report_card.lock'), ('report_card.publish'),
    ('exam.view'), ('exam.manage'), ('exam.publish'),
    ('hall_ticket.issue'), ('hall_ticket.view'),
    ('board_export.view'), ('board_export.manage'),
    ('announcement.view'), ('announcement.manage'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== registrar: admissions funnel and the student record =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'registrar', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('student.manage'), ('guardian.view'),
    ('staff.view'), ('directory.view'),
    ('enrolment.view'), ('enrolment.manage'), ('election.manage'),
    ('admission.view'), ('admission.manage'), ('admission.decide'), ('admission.enrol'),
    ('calendar.view'), ('attendance.view'),
    ('board_export.view'), ('board_export.manage'),
    ('announcement.view'), ('announcement.manage'), ('message.participate'),
    ('rollover.view'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== class teacher: their homeroom's day, plus teaching =====
-- The grant is school-wide because staff_role records it that way; narrowing a
-- teacher to their own sections is STF-05 and is enforced today only where a
-- contextual authorizer exists (AttendanceAuthorizer).
INSERT INTO role_perm (role_code, perm_code)
SELECT 'class_teacher', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('directory.view'), ('enrolment.view'),
    ('calendar.view'),
    ('attendance.view'), ('attendance.mark'), ('attendance.amend.request'),
    ('leave.view'), ('leave.apply'),
    ('timetable.view'), ('cover.view'),
    ('assessment.view'), ('assessment.manage'), ('mark.view'), ('mark.enter'),
    ('report_card.view'), ('report_card.generate'),
    ('exam.view'), ('hall_ticket.view'),
    ('lms.content.view'), ('lms.content.manage'), ('lms.grade'),
    ('announcement.view'), ('message.participate'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== subject teacher: the periods they teach =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'subject_teacher', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('directory.view'), ('calendar.view'),
    ('attendance.view'), ('attendance.mark'), ('attendance.amend.request'), ('leave.apply'),
    ('timetable.view'), ('cover.view'),
    ('assessment.view'), ('assessment.manage'), ('mark.view'), ('mark.enter'),
    ('exam.view'),
    ('lms.content.view'), ('lms.content.manage'), ('lms.grade'),
    ('announcement.view'), ('message.participate'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== accountant: the money, and just enough of the student to bill them =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'accountant', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('guardian.view'), ('directory.view'),
    ('enrolment.view'), ('calendar.view'),
    ('fee.structure.view'), ('fee.structure.manage'),
    ('fee.invoice.view'), ('fee.invoice.manage'), ('fee.payment.record'),
    ('fee.adjustment.manage'), ('fee.concession.manage'), ('fee.report.view'), ('dunning.manage'),
    ('transport.view'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== librarian =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'librarian', p.perm FROM (VALUES
    ('student.view'), ('staff.view'), ('directory.view'),
    ('library.view'), ('library.manage'), ('library.circulate'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== front office: the counter =====
INSERT INTO role_perm (role_code, perm_code)
SELECT 'front_office', p.perm FROM (VALUES
    ('structure.view'), ('student.view'), ('guardian.view'), ('directory.view'),
    ('enrolment.view'), ('calendar.view'),
    ('admission.view'), ('admission.manage'),
    ('attendance.view'), ('leave.view'),
    ('announcement.view'), ('message.participate'),
    ('transport.view'), ('transport.track'),
    ('dashboard.view'), ('file.upload'), ('file.download')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- ===== driver =====
-- `student.view` is wider than it should be: a driver needs the students on
-- their own route, and this grants them the school. Narrowing it needs a
-- route-scoped student read, which transport does not have yet — logged
-- rather than papered over, because the alternative today is either a broken
-- check-in screen or a silent over-grant nobody wrote down.
INSERT INTO role_perm (role_code, perm_code)
SELECT 'driver', p.perm FROM (VALUES
    ('structure.view'), ('student.view'),
    ('transport.view'), ('transport.drive'),
    ('dashboard.view')
) AS p(perm)
ON CONFLICT DO NOTHING;

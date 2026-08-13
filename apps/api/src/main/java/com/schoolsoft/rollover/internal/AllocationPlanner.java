package com.schoolsoft.rollover.internal;

import com.schoolsoft.rollover.api.RolloverRunDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans where every child goes next year (YEC-03/04/05, GRAD-01).
 *
 * The decision itself is not made here — it is read from the report card's
 * {@code promotion_decision}, which is a teacher's and a principal's judgement.
 * What this does is turn that decision into a seat:
 *
 * <ul>
 *   <li><b>promote</b> → the next grade by {@code sort_order}; in the terminal
 *       grade there is nothing above, so the child graduates and the plan says
 *       so rather than silently dropping them;</li>
 *   <li><b>detain</b> → the same grade again, next year's copy of it;</li>
 *   <li><b>graduate</b> → no seat at all.</li>
 * </ul>
 *
 * Section choice keeps a class together where it can: 5A's children land in
 * 6A, siblings land beside each other, and capacity is respected — a full
 * grade produces unplaced rows, which is a question for the school rather than
 * a 31st chair the system invented.
 *
 * Nothing here writes to enrolment. The plan is inert until commit, which is
 * what makes it editable and disposable.
 */
@Service
public class AllocationPlanner {

    private final JdbcTemplate jdbc;

    public AllocationPlanner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Result(int planned, int promoting, int detaining, int graduating,
                         int unplaced, int withoutDecision) {}

    private record Candidate(UUID studentId, UUID enrolmentId, UUID sectionId, UUID gradeId,
                             int gradeOrder, String sectionCode, String admissionNo, UUID familyId,
                             String decision) {}

    private record TargetSection(UUID id, String code, Integer capacity, int taken) {}

    @Transactional
    public Result plan(RolloverRunDto run) {
        // Applied rows are somebody's history now; only the untouched plan is
        // rebuilt, so re-planning after a partial commit is safe.
        jdbc.update("DELETE FROM rollover_allocation WHERE rollover_run_id = ? AND state <> 'applied'",
            run.id());

        List<Candidate> candidates = candidates(run);
        Map<UUID, List<TargetSection>> sectionsByGrade = targetSections(run.toAcademicYearId());
        // The ladder, in the school's own order. Position rather than
        // sort_order arithmetic: a school that numbers its grades 10, 20, 30
        // still has a next grade.
        List<UUID> ladder = gradeLadder(run.schoolId());
        Map<UUID, Integer> rungOf = new HashMap<>();
        for (int i = 0; i < ladder.size(); i++) rungOf.put(ladder.get(i), i);

        // Seats claimed by this plan, on top of whatever is already enrolled.
        Map<UUID, Integer> claimed = new HashMap<>();
        // Where a family's first child of a grade landed, so twins stay together.
        Map<String, UUID> familyPlacement = new HashMap<>();
        // How many children each household is sending into each grade. A pair of
        // twins has to be placed as a pair: choosing a section with one seat
        // left for the first of them is how they end up in different rooms.
        // Seats held for siblings still to be reached in the queue.
        Map<String, Integer> reserved = new HashMap<>();
        Map<String, Integer> familySize = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.familyId() == null || candidate.decision() == null) continue;
            familySize.merge(cohortKey(candidate), 1, Integer::sum);
        }

        List<Object[]> rows = new ArrayList<>();
        int promoting = 0, detaining = 0, graduating = 0, unplaced = 0, withoutDecision = 0;
        int batchNo = 0;
        int inBatch = 0;

        for (Candidate candidate : candidates) {
            String decision = candidate.decision();
            String note = null;
            UUID targetGrade = null;

            if (decision == null) {
                // No decision, no move. The readiness check named these before
                // anybody got this far; recording them keeps the list visible
                // instead of leaving a child silently absent from the plan.
                rows.add(row(run, candidate, "promote", null, "skipped",
                    "No promotion decision on this year's report card", batchNo));
                withoutDecision++;
                continue;
            }

            if ("promote".equals(decision)) {
                Integer rung = rungOf.get(candidate.gradeId());
                targetGrade = rung == null || rung + 1 >= ladder.size() ? null : ladder.get(rung + 1);
                if (targetGrade == null) {
                    // Top of the school: promotion out of the last grade is
                    // graduation, whatever the card says.
                    decision = "graduate";
                    note = "Promoted out of the highest grade — recorded as graduating";
                }
            } else if ("detain".equals(decision)) {
                targetGrade = candidate.gradeId();
            }

            if ("graduate".equals(decision)) {
                rows.add(row(run, candidate, "graduate", null, "planned", note, batchNo));
                graduating++;
            } else {
                int together = candidate.familyId() == null ? 1
                    : familySize.getOrDefault(cohortKey(candidate), 1);
                boolean seatHeld = reserved.getOrDefault(cohortKey(candidate), 0) > 0;
                UUID seat = chooseSection(sectionsByGrade.get(targetGrade), candidate, claimed,
                    familyPlacement, targetGrade, together, seatHeld);
                if (seat == null) {
                    rows.add(row(run, candidate, decision, null, "planned",
                        "No seat in the target grade — add a section or raise capacity", batchNo));
                    unplaced++;
                } else {
                    String key = cohortKey(candidate);
                    Integer held = reserved.get(key);
                    if (held != null && held > 0) {
                        // A sibling reserved this seat when the first of the
                        // household was placed; taking it now claims nothing new.
                        reserved.put(key, held - 1);
                    } else if (together > 1 && candidate.familyId() != null) {
                        // First of the household: hold the whole family's seats
                        // now, or the children behind them in the queue take
                        // the one their sibling needs.
                        claimed.merge(seat, together, Integer::sum);
                        reserved.put(key, together - 1);
                    } else {
                        claimed.merge(seat, 1, Integer::sum);
                    }
                    if (candidate.familyId() != null) {
                        familyPlacement.putIfAbsent(candidate.familyId() + ":" + targetGrade, seat);
                    }
                    rows.add(row(run, candidate, decision, seat, "planned", note, batchNo));
                }
                if ("promote".equals(decision)) promoting++; else detaining++;
            }

            if (++inBatch >= run.batchSize()) {
                inBatch = 0;
                batchNo++;
            }
        }

        if (!rows.isEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO rollover_allocation (id, rollover_run_id, school_id, student_id, " +
                "  from_enrolment_id, from_section_id, decision, to_section_id, state, note, batch_no) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        }
        return new Result(rows.size(), promoting, detaining, graduating, unplaced, withoutDecision);
    }

    private Object[] row(RolloverRunDto run, Candidate candidate, String decision, UUID toSection,
                         String state, String note, int batchNo) {
        return new Object[]{
            UUID.randomUUID(), run.id(), run.schoolId(), candidate.studentId(), candidate.enrolmentId(),
            candidate.sectionId(), decision, toSection, state, note, batchNo
        };
    }

    /**
     * A sibling's section first, then the same section code — 5A becomes 6A,
     * and a class that spent a year together stays together — then whatever
     * has room, in code order.
     *
     * {@code together} is how many children of this household are coming into
     * the grade. The first of them needs a section with room for all of them,
     * or the second finds the door shut and the family runs two school
     * routines. If no section can take the pair, they are placed separately
     * rather than left unplaced: a seat each beats no seat at all.
     */
    private UUID chooseSection(List<TargetSection> sections, Candidate candidate,
                               Map<UUID, Integer> claimed, Map<String, UUID> familyPlacement,
                               UUID targetGrade, int together, boolean seatHeld) {
        if (sections == null || sections.isEmpty()) return null;

        UUID siblingSection = candidate.familyId() == null ? null
            : familyPlacement.get(candidate.familyId() + ":" + targetGrade);

        // Their sibling's placement already paid for this seat; the capacity
        // check would now count it a second time and send them elsewhere.
        if (seatHeld && siblingSection != null) return siblingSection;

        List<TargetSection> ordered = new ArrayList<>(sections);
        ordered.sort((a, b) -> {
            int rankA = rank(a, candidate.sectionCode(), siblingSection);
            int rankB = rank(b, candidate.sectionCode(), siblingSection);
            return rankA != rankB ? Integer.compare(rankA, rankB) : a.code().compareTo(b.code());
        });

        if (together > 1 && siblingSection == null) {
            for (TargetSection section : ordered) {
                if (hasRoom(section, claimed, together)) return section.id();
            }
        }
        for (TargetSection section : ordered) {
            if (hasRoom(section, claimed, 1)) return section.id();
        }
        return null;
    }

    private int rank(TargetSection section, String fromCode, UUID siblingSection) {
        if (siblingSection != null && section.id().equals(siblingSection)) return 0;
        if (section.code().equals(fromCode)) return 1;
        return 2;
    }

    private boolean hasRoom(TargetSection section, Map<UUID, Integer> claimed, int seats) {
        if (section.capacity() == null) return true;
        return section.taken() + claimed.getOrDefault(section.id(), 0) + seats <= section.capacity();
    }

    /** One household's children heading into one grade — the group placed as a unit. */
    private String cohortKey(Candidate candidate) {
        return candidate.familyId() + ":" + candidate.gradeId() + ":" + candidate.decision();
    }

    private List<Candidate> candidates(RolloverRunDto run) {
        return jdbc.query(
            "SELECT e.student_id, e.id AS enrolment_id, e.section_id, s.grade_id, g.sort_order, " +
            "       s.code AS section_code, st.admission_no, st.family_id, " +
            "       (SELECT rc.promotion_decision FROM report_card rc " +
            "        WHERE rc.student_id = e.student_id AND rc.academic_year_id = e.academic_year_id " +
            "          AND rc.promotion_decision IS NOT NULL " +
            "        ORDER BY rc.version DESC, rc.generated_at DESC LIMIT 1) AS decision " +
            "FROM enrolment e " +
            "JOIN section s ON s.id = e.section_id " +
            "JOIN grade g ON g.id = s.grade_id " +
            "JOIN student st ON st.id = e.student_id " +
            "WHERE e.academic_year_id = ? AND e.status = 'active' " +
            "  AND NOT EXISTS (SELECT 1 FROM rollover_allocation ra " +
            "                  WHERE ra.rollover_run_id = ? AND ra.student_id = e.student_id) " +
            "ORDER BY g.sort_order, s.code, st.admission_no",
            (rs, i) -> new Candidate(
                UUID.fromString(rs.getString("student_id")),
                UUID.fromString(rs.getString("enrolment_id")),
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getInt("sort_order"),
                rs.getString("section_code"),
                rs.getString("admission_no"),
                rs.getString("family_id") == null ? null : UUID.fromString(rs.getString("family_id")),
                rs.getString("decision")),
            run.fromAcademicYearId(), run.id());
    }

    private Map<UUID, List<TargetSection>> targetSections(UUID toAy) {
        Map<UUID, List<TargetSection>> byGrade = new LinkedHashMap<>();
        jdbc.query(
            "SELECT s.id, s.grade_id, s.code, s.capacity, " +
            "       (SELECT count(*) FROM enrolment e WHERE e.section_id = s.id AND e.status = 'active') AS taken " +
            "FROM section s WHERE s.academic_year_id = ? ORDER BY s.code",
            rs -> {
                UUID gradeId = UUID.fromString(rs.getString("grade_id"));
                byGrade.computeIfAbsent(gradeId, k -> new ArrayList<>()).add(new TargetSection(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("code"),
                    (Integer) rs.getObject("capacity"),
                    rs.getInt("taken")));
            },
            toAy);
        return byGrade;
    }

    private List<UUID> gradeLadder(UUID schoolId) {
        return jdbc.query("SELECT id FROM grade WHERE school_id = ? ORDER BY sort_order, code",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId);
    }
}

"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { SyncConflict, SyncConflicts } from "@schoolsoft/ui";
import {
  ApiError,
  attendanceForSectionOnDate,
  AttendanceSyncEntry,
  AttendanceSyncResultDto,
  EnrolmentDto,
  getSession,
  listSections,
  rosterForSection,
  syncAttendance,
  SectionDto,
  Session,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

const STATUSES = ["present", "absent", "late", "leave", "excused", "half_day"];

/**
 * A register marked while there was no connection, waiting on this device.
 * Each entry keeps what the teacher was looking at when they marked it, which
 * is what lets the server tell a mark from a stale one when it finally arrives.
 */
type QueuedRegister = {
  schoolId: string;
  sectionId: string;
  onDate: string;
  /** "Grade 5-B · 2026-08-24", for naming it once the teacher has moved on to another class. */
  where: string;
  entries: (AttendanceSyncEntry & { who: string })[];
};

/** A mark that was sent and did not land, with enough to send it again. */
type PendingConflict = SyncConflict & { schoolId: string; sectionId: string; onDate: string; studentId: string };

function outboxKey(session: Session): string {
  // Per person: a staffroom tablet is shared, and one teacher's unsent
  // register is not the next one's to send.
  return `schoolsoft_teacher_attendance_outbox:${session.userAccountId}`;
}

function readOutbox(session: Session): QueuedRegister[] {
  try {
    return JSON.parse(window.localStorage.getItem(outboxKey(session)) ?? "[]") as QueuedRegister[];
  } catch {
    return [];
  }
}

function writeOutbox(session: Session, queue: QueuedRegister[]) {
  try {
    window.localStorage.setItem(outboxKey(session), JSON.stringify(queue));
  } catch {
    // Storage refused (private window, full disk): the register stays on
    // screen and the banner still says it has not been sent.
  }
}

function todayIso(): string {
  // The local calendar date. toISOString() is UTC, which in India is still
  // yesterday until 05:30 — and a register opened early landed on the wrong day.
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export default function AttendancePage() {
  return (
    <Suspense fallback={null}>
      <AttendanceInner />
    </Suspense>
  );
}

function AttendanceInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [session, setSessionState] = useState<Session | null>(null);
  const [mySections, setMySections] = useState<SectionDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [onDate, setOnDate] = useState(todayIso());
  const [roster, setRoster] = useState<EnrolmentDto[] | null>(null);
  // Which device reported each student at the gate that day, if one did.
  const [gate, setGate] = useState<Record<string, string>>({});
  const [statuses, setStatuses] = useState<Record<string, string>>({});
  // The markedAt read for each student when the register was loaded — what
  // every mark made from this screen is sent against.
  const [seen, setSeen] = useState<Record<string, string>>({});
  const [outbox, setOutbox] = useState<QueuedRegister[]>([]);
  const [conflicts, setConflicts] = useState<PendingConflict[]>([]);
  // The register on screen, for the sync that finishes after the teacher has
  // moved to another class.
  const showing = useRef({ sectionId: "", onDate: "" });
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;

    Promise.all([timetableForTeacher(s.subjectId), listSections(s.schoolId)])
      .then(([tt, allSections]: [TimetableSlotDto[], SectionDto[]]) => {
        const ids = Array.from(new Set(tt.map((t) => t.sectionId)));
        const mine = allSections.filter((sec) => ids.includes(sec.id));
        setMySections(mine);
        const preselect = searchParams.get("section");
        if (preselect && ids.includes(preselect)) {
          setSectionId(preselect);
        } else if (mine.length > 0) {
          setSectionId(mine[0].id);
        }
      })
      .catch((err) => setError(describeError(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  useEffect(() => {
    if (!sectionId || !onDate) return;
    setLoading(true);
    setError(null);
    setSaveMessage(null);
    Promise.all([rosterForSection(sectionId), attendanceForSectionOnDate(sectionId, onDate)])
      .then(([r, existing]) => {
        setRoster(r);
        const initial: Record<string, string> = {};
        const seen: Record<string, string> = {};
        const read: Record<string, string> = {};
        for (const enr of r) {
          // Jackson's non_null inclusion omits periodNo from the JSON entirely when it's
          // null, rather than serializing `null` — so it arrives here as `undefined`, not
          // `null`. Loose equality catches both.
          const match = existing.find((e) => e.studentId === enr.studentId && e.periodNo == null);
          initial[enr.studentId] = match?.status ?? "present";
          if (match?.gateSeenAt) seen[enr.studentId] = match.gateSource ?? "biometric";
          if (match) read[enr.studentId] = match.markedAt;
        }
        // A register still waiting to be sent is what the teacher marked, and
        // it is what they should see on coming back to it.
        const waiting = session
          ? readOutbox(session).find((q) => q.sectionId === sectionId && q.onDate === onDate)
          : undefined;
        waiting?.entries.forEach((e) => (initial[e.studentId] = e.status));
        setStatuses(initial);
        setGate(seen);
        setSeen(read);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [sectionId, onDate]);

  useEffect(() => {
    showing.current = { sectionId, onDate };
  }, [sectionId, onDate]);

  /** Takes in what the server said about one register: what landed, and what needs a decision. */
  const absorb = useCallback((reg: QueuedRegister, result: AttendanceSyncResultDto) => {
    const current = showing.current.sectionId === reg.sectionId && showing.current.onDate === reg.onDate;
    if (current) {
      setSeen((prev) => {
        const next = { ...prev };
        result.applied.forEach((r) => (next[r.studentId] = r.markedAt));
        return next;
      });
    }
    const raised: PendingConflict[] = result.conflicts.map((c) => ({
      key: `${reg.sectionId}:${reg.onDate}:${c.studentId}`,
      who: reg.entries.find((e) => e.studentId === c.studentId)?.who ?? "A student",
      where: reg.where,
      kind: c.kind,
      yours: c.yours,
      theirs: c.theirs && { status: c.theirs.status, source: c.theirs.source, markedAt: c.theirs.markedAt },
      message: c.message,
      schoolId: reg.schoolId,
      sectionId: reg.sectionId,
      onDate: reg.onDate,
      studentId: c.studentId,
    }));
    setConflicts((prev) => [...prev.filter((p) => !raised.some((r) => r.key === p.key)), ...raised]);
    return raised.length;
  }, []);

  /**
   * Sends everything waiting on this device, oldest first, and stops at the
   * first one the network will not carry. Reads the queue from storage rather
   * than from state: it runs from the `online` event, long after the render
   * that registered it.
   */
  const flush = useCallback(async () => {
    const s = getSession();
    if (!s) return;
    let queue = readOutbox(s);
    while (queue.length > 0) {
      const reg = queue[0];
      try {
        const result = await syncAttendance(
          reg.schoolId, reg.sectionId, reg.onDate, reg.entries.map(({ who: _who, ...entry }) => entry));
        const undecided = absorb(reg, result);
        setSaveMessage(
          `Sent ${reg.where}: ${result.applied.length} saved` +
            (undecided > 0 ? `, ${undecided} need${undecided === 1 ? "s" : ""} a decision below.` : ".")
        );
      } catch (err) {
        if (!(err instanceof ApiError)) break; // still no connection — keep it, try again later
        // The server answered and will not take it (the session's rights
        // changed, the year closed). Retrying forever would hide that.
        setError(`${reg.where} could not be sent: ${err.userMessage}`);
      }
      queue = queue.slice(1);
      writeOutbox(s, queue);
    }
    setOutbox(queue);
  }, [absorb]);

  useEffect(() => {
    if (!session) return;
    setOutbox(readOutbox(session));
    flush();
    window.addEventListener("online", flush);
    return () => window.removeEventListener("online", flush);
  }, [session, flush]);

  async function onSave() {
    if (!session || !roster) return;
    setSaving(true);
    setError(null);
    setSaveMessage(null);
    const section = mySections?.find((sec) => sec.id === sectionId);
    const reg: QueuedRegister = {
      schoolId: session.schoolId,
      sectionId,
      onDate,
      where: `${section ? `${section.gradeName}-${section.code}` : "Register"} · ${onDate}`,
      entries: roster.map((r) => ({
        studentId: r.studentId,
        status: statuses[r.studentId] ?? "present",
        seenMarkedAt: seen[r.studentId],
        who: `Roll ${r.rollNo ?? "—"}`,
      })),
    };
    try {
      const result = await syncAttendance(
        reg.schoolId, reg.sectionId, reg.onDate, reg.entries.map(({ who: _who, ...entry }) => entry));
      const undecided = absorb(reg, result);
      setSaveMessage(
        undecided > 0
          ? `Saved ${result.applied.length} of ${roster.length}. ${undecided} changed while you were marking — decide below.`
          : `Saved attendance for ${roster.length} student(s).`
      );
    } catch (err) {
      if (err instanceof ApiError) {
        setError(describeError(err));
      } else {
        // No answer at all: the network, not the server. The register is
        // kept here and sent when there is a connection — and says so, so
        // nobody walks away thinking it reached the office.
        const queue = [...readOutbox(session).filter((q) => !(q.sectionId === sectionId && q.onDate === onDate)), reg];
        writeOutbox(session, queue);
        setOutbox(queue);
        setSaveMessage(null);
      }
    } finally {
      setSaving(false);
    }
  }

  /** Overrules what is on the register with the mark that was sent, now that the teacher has seen both. */
  async function onUseMine(c: SyncConflict) {
    const conflict = conflicts.find((p) => p.key === c.key);
    if (!conflict) return;
    setSaving(true);
    setError(null);
    try {
      const reg: QueuedRegister = {
        schoolId: conflict.schoolId, sectionId: conflict.sectionId, onDate: conflict.onDate,
        where: conflict.where ?? "", entries: [{ studentId: conflict.studentId, status: conflict.yours,
          seenMarkedAt: conflict.theirs?.markedAt, who: conflict.who }],
      };
      const result = await syncAttendance(reg.schoolId, reg.sectionId, reg.onDate,
        [{ studentId: conflict.studentId, status: conflict.yours, seenMarkedAt: conflict.theirs?.markedAt }]);
      setConflicts((prev) => prev.filter((p) => p.key !== c.key));
      if (absorb(reg, result) === 0) setSaveMessage(`${conflict.who}: saved as ${conflict.yours.replace("_", " ")}.`);
    } catch (err) {
      setError(err instanceof ApiError ? describeError(err) : "No connection — try again when you are back online.");
    } finally {
      setSaving(false);
    }
  }

  /** Accepts what is on the register and drops the mark that was sent. */
  function onKeepTheirs(c: SyncConflict) {
    const conflict = conflicts.find((p) => p.key === c.key);
    setConflicts((prev) => prev.filter((p) => p.key !== c.key));
    if (!conflict?.theirs) return;
    if (showing.current.sectionId === conflict.sectionId && showing.current.onDate === conflict.onDate) {
      const theirs = conflict.theirs;
      setStatuses((prev) => ({ ...prev, [conflict.studentId]: theirs.status }));
      setSeen((prev) => ({ ...prev, [conflict.studentId]: theirs.markedAt }));
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Attendance</h2>
          <p className="hint">This account isn&apos;t linked to a staff record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Mark attendance</h2>
        <div className="form-row" style={{ flexDirection: "column" }}>
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!mySections}>
            {mySections?.length === 0 && <option value="">No sections assigned</option>}
            {mySections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
          <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
        </div>

        {outbox.length > 0 && (
          <div className="outbox-banner" role="status">
            <strong>Not sent yet.</strong> {outbox.length === 1 ? "One register is" : `${outbox.length} registers are`}{" "}
            saved on this device and will be sent when there is a connection:{" "}
            {outbox.map((q) => q.where).join("; ")}.{" "}
            <button type="button" className="link-button" onClick={flush} disabled={saving}>
              Send now
            </button>
          </div>
        )}

        {conflicts.length > 0 && (
          <div className="conflict-panel">
            <strong>
              {conflicts.length === 1 ? "One mark needs" : `${conflicts.length} marks need`} a decision
            </strong>
            <div className="hint">The rest of the register was saved.</div>
            <SyncConflicts conflicts={conflicts} busy={saving} onUseMine={onUseMine} onKeepTheirs={onKeepTheirs} />
          </div>
        )}

        {loading && <p className="hint">Loading roster…</p>}
        {error && <div className="error-banner">{error}</div>}
        {saveMessage && <p className="hint">{saveMessage}</p>}

        {roster && roster.length === 0 && <p className="empty-note">No active students in this section.</p>}

        {roster && roster.length > 0 && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Roll</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {roster
                  .slice()
                  .sort((a, b) => (a.rollNo ?? "").localeCompare(b.rollNo ?? ""))
                  .map((r) => (
                    <tr key={r.studentId}>
                      <td>{r.rollNo ?? "—"}</td>
                      <td>
                        <select
                          value={statuses[r.studentId] ?? "present"}
                          onChange={(e) => setStatuses((s) => ({ ...s, [r.studentId]: e.target.value }))}
                        >
                          {STATUSES.map((st) => (
                            <option key={st} value={st}>
                              {st}
                            </option>
                          ))}
                        </select>
                        <GateNote source={gate[r.studentId]} status={statuses[r.studentId] ?? "present"} />
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
            <div style={{ marginTop: 14 }}>
              <button type="button" onClick={onSave} disabled={saving || loading} style={{ width: "100%" }}>
                {saving ? "Saving…" : "Save attendance"}
              </button>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

/**
 * The gate and the register, side by side. A device never changes a mark a
 * person made, so when a card was read on a day the register says the child
 * was not there, this is the only place the two meet — and somebody should
 * look, because one of them is wrong and the child may be in the building.
 */
function GateNote({ source, status }: { source: string | undefined; status: string }) {
  if (!source) return null;
  const device = source === "rfid" ? "card reader" : "biometric gate";
  if (status === "absent" || status === "leave") {
    return (
      <div className="gate-disagrees" role="note">
        The {device} saw this child arrive. Check before saving them as {status === "leave" ? "on leave" : "absent"}.
      </div>
    );
  }
  return <div className="hint">Seen at the {device}</div>;
}

function describeError(err: unknown): string {
  // A subject teacher holds no attendance permission: the register is the
  // class teacher's. Say that, rather than "you do not have permission".
  if (err instanceof ApiError && err.status === 403) {
    return "Taking the register is not part of your role here — the class teacher or the office marks attendance for this section.";
  }
  if (err instanceof ApiError) return err.userMessage;
  return err instanceof Error ? err.message : "Unknown error";
}

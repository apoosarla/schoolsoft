"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  ApiError,
  attendanceForSectionOnDate,
  EnrolmentDto,
  getSession,
  listSections,
  markAttendanceBulk,
  rosterForSection,
  SectionDto,
  Session,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

const STATUSES = ["present", "absent", "late", "leave", "excused", "half_day"];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
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
  const [statuses, setStatuses] = useState<Record<string, string>>({});
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
    if (!sectionId) return;
    setLoading(true);
    setError(null);
    setSaveMessage(null);
    Promise.all([rosterForSection(sectionId), attendanceForSectionOnDate(sectionId, onDate)])
      .then(([r, existing]) => {
        setRoster(r);
        const initial: Record<string, string> = {};
        for (const enr of r) {
          // Jackson's non_null inclusion omits periodNo from the JSON entirely when it's
          // null, rather than serializing `null` — so it arrives here as `undefined`, not
          // `null`. Loose equality catches both.
          const match = existing.find((e) => e.studentId === enr.studentId && e.periodNo == null);
          initial[enr.studentId] = match?.status ?? "present";
        }
        setStatuses(initial);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [sectionId, onDate]);

  async function onSave() {
    if (!session || !roster) return;
    setSaving(true);
    setError(null);
    setSaveMessage(null);
    try {
      await markAttendanceBulk(
        session.schoolId,
        sectionId,
        onDate,
        roster.map((r) => ({ studentId: r.studentId, status: statuses[r.studentId] ?? "present" }))
      );
      setSaveMessage(`Saved attendance for ${roster.length} student(s).`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
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

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
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
} from "@/lib/api";

const STATUSES = ["present", "absent", "late", "leave", "excused", "half_day"];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

export default function AttendancePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
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
    listSections(s.schoolId)
      .then((secs) => {
        setSections(secs);
        if (secs.length > 0) setSectionId(secs[0].id);
      })
      .catch((err) => setError(describeError(err)));
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
          const match = existing.find((e) => e.studentId === enr.studentId && e.periodNo === null);
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

  return (
    <main className="shell">
      <div className="panel">
        <h2>Mark attendance</h2>
        <div className="form-row">
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!sections}>
            {sections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
          <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
          <button type="button" onClick={onSave} disabled={saving || loading || !roster || roster.length === 0}>
            {saving ? "Saving…" : "Save attendance"}
          </button>
        </div>

        {loading && <p className="hint">Loading roster…</p>}
        {error && <div className="error-banner">{error}</div>}
        {saveMessage && <p className="hint">{saveMessage}</p>}

        {roster && roster.length === 0 && <p className="hint">No active students in this section.</p>}

        {roster && roster.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Roll no.</th>
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
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

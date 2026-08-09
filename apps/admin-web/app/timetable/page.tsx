"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  createTimetableSlot,
  deleteTimetableSlot,
  getSession,
  listSections,
  listStaff,
  listSubjects,
  SectionDto,
  Session,
  StaffDto,
  SubjectDto,
  timetableForSection,
  TimetableSlotDto,
} from "@/lib/api";

const DAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

const emptyForm = {
  subjectId: "",
  teacherStaffId: "",
  dayOfWeek: "1",
  periodNo: "1",
  startsAt: "09:00",
  endsAt: "09:45",
  room: "",
  effectiveFrom: todayIso(),
  effectiveTo: "",
};

export default function TimetablePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [staff, setStaff] = useState<StaffDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [slots, setSlots] = useState<TimetableSlotDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    Promise.all([listSections(s.schoolId), listSubjects(s.schoolId), listStaff(s.schoolId)])
      .then(([secs, subs, stf]) => {
        setSections(secs);
        setSubjects(subs);
        setStaff(stf);
        if (secs.length > 0) setSectionId(secs[0].id);
        setForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "", teacherStaffId: stf[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refresh(id: string) {
    setLoading(true);
    setError(null);
    timetableForSection(id)
      .then(setSlots)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    if (!sectionId) return;
    refresh(sectionId);
  }, [sectionId]);

  async function onCreate() {
    setCreating(true);
    setError(null);
    try {
      await createTimetableSlot({
        sectionId,
        subjectId: form.subjectId,
        teacherStaffId: form.teacherStaffId,
        dayOfWeek: Number(form.dayOfWeek),
        periodNo: Number(form.periodNo),
        startsAt: form.startsAt,
        endsAt: form.endsAt,
        room: form.room || undefined,
        effectiveFrom: form.effectiveFrom,
        effectiveTo: form.effectiveTo || undefined,
      });
      setShowForm(false);
      refresh(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(id: string) {
    setDeletingId(id);
    setError(null);
    try {
      await deleteTimetableSlot(id);
      refresh(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setDeletingId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Timetable</h2>
          <button type="button" onClick={() => setShowForm((v) => !v)} disabled={!subjects || subjects.length === 0}>
            {showForm ? "Cancel" : "Add slot"}
          </button>
        </div>
        <div className="form-row">
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!sections}>
            {sections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
        </div>

        {showForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <select value={form.subjectId} onChange={(e) => setForm((f) => ({ ...f, subjectId: e.target.value }))}>
              {subjects?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <select
              value={form.teacherStaffId}
              onChange={(e) => setForm((f) => ({ ...f, teacherStaffId: e.target.value }))}
            >
              {staff?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.firstName} {s.lastName ?? ""}
                </option>
              ))}
            </select>
            <select value={form.dayOfWeek} onChange={(e) => setForm((f) => ({ ...f, dayOfWeek: e.target.value }))}>
              {DAY_NAMES.map((d, i) => (
                <option key={i} value={i}>
                  {d}
                </option>
              ))}
            </select>
            <input
              type="number"
              min={1}
              placeholder="Period"
              value={form.periodNo}
              onChange={(e) => setForm((f) => ({ ...f, periodNo: e.target.value }))}
              style={{ maxWidth: 80 }}
            />
            <input
              type="time"
              value={form.startsAt}
              onChange={(e) => setForm((f) => ({ ...f, startsAt: e.target.value }))}
            />
            <input type="time" value={form.endsAt} onChange={(e) => setForm((f) => ({ ...f, endsAt: e.target.value }))} />
            <input
              placeholder="Room"
              value={form.room}
              onChange={(e) => setForm((f) => ({ ...f, room: e.target.value }))}
              style={{ maxWidth: 100 }}
            />
            <input
              type="date"
              value={form.effectiveFrom}
              onChange={(e) => setForm((f) => ({ ...f, effectiveFrom: e.target.value }))}
            />
            <button
              type="button"
              onClick={onCreate}
              disabled={creating || !form.subjectId || !form.teacherStaffId || !sectionId}
            >
              {creating ? "Adding…" : "Add slot"}
            </button>
          </div>
        )}

        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}
        {slots && slots.length === 0 && <p className="hint">No timetable slots for this section yet.</p>}

        {slots && slots.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Period</th>
                <th>Time</th>
                <th>Subject</th>
                <th>Teacher</th>
                <th>Room</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {slots
                .slice()
                .sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.periodNo - b.periodNo)
                .map((slot) => {
                  const teacher = staff?.find((s) => s.id === slot.teacherStaffId);
                  return (
                    <tr key={slot.id}>
                      <td>{DAY_NAMES[slot.dayOfWeek]}</td>
                      <td>{slot.periodNo}</td>
                      <td>
                        {slot.startsAt}–{slot.endsAt}
                      </td>
                      <td>{slot.subjectName}</td>
                      <td>{teacher ? `${teacher.firstName} ${teacher.lastName ?? ""}` : "—"}</td>
                      <td>{slot.room ?? "—"}</td>
                      <td>
                        <button type="button" onClick={() => onDelete(slot.id)} disabled={deletingId === slot.id}>
                          {deletingId === slot.id ? "…" : "Delete"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
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

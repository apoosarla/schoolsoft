"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  getSession,
  listSections,
  SectionDto,
  Session,
  teacherDay,
  TeacherDayDto,
} from "@/lib/api";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function nowHHMM(): string {
  const d = new Date();
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") + ":00";
}

function sectionLabel(sections: SectionDto[] | null, sectionId: string): string {
  const s = sections?.find((x) => x.id === sectionId);
  return s ? `${s.gradeName}-${s.code}` : "Section";
}

/**
 * The teacher's morning, resolved for one date rather than a week grid: the
 * school calendar says whether there is school at all, periods handed to a
 * substitute are gone, and periods taken on for somebody absent are here —
 * along with the permission to mark that class's register.
 */
export default function TodayPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [day, setDay] = useState<TeacherDayDto | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) {
      setLoading(false);
      return;
    }
    Promise.all([teacherDay(s.subjectId, todayIso()), listSections(s.schoolId)])
      .then(([d, secs]) => {
        setDay(d);
        setSections(secs);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [router]);

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Today</h2>
          <p className="hint">
            This account isn&apos;t linked to a staff record, so there&apos;s no timetable to show.
          </p>
        </div>
      </main>
    );
  }

  const now = nowHHMM();
  const periods = [
    ...(day?.slots ?? []).map((slot) => ({
      key: slot.id,
      periodNo: slot.periodNo,
      startsAt: slot.startsAt,
      endsAt: slot.endsAt,
      title: slot.subjectName,
      sectionId: slot.sectionId,
      room: slot.room,
      cover: null as string | null,
    })),
    ...(day?.covering ?? []).map((cover) => ({
      key: cover.id,
      periodNo: cover.periodNo,
      startsAt: cover.startsAt,
      endsAt: cover.endsAt,
      title: cover.subjectName,
      sectionId: cover.sectionId,
      room: cover.room,
      cover: `Covering for ${cover.absentStaffName}`,
    })),
  ].sort((a, b) => a.periodNo - b.periodNo);

  return (
    <main className="shell">
      <div className="panel">
        <h2>Today&apos;s schedule</h2>
        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}

        {/* A closed day says why, rather than rendering an empty grid the
            reader has to interpret. */}
        {day && !day.working && (
          <p className="empty-note">School closed today{day.reason ? ` — ${day.reason}` : ""}.</p>
        )}

        {day?.working && periods.length === 0 && (
          <p className="empty-note">No periods scheduled for you today.</p>
        )}

        {day?.working && periods.length > 0 && (
          <div className="timeline" style={{ marginTop: 12 }}>
            {periods.map((p) => {
              const isNow = now >= p.startsAt && now < p.endsAt;
              return (
                <div className={"tl-item" + (isNow ? " now" : "")} key={p.key}>
                  <div className="tl-time">
                    P{p.periodNo} · {p.startsAt.slice(0, 5)}–{p.endsAt.slice(0, 5)}
                  </div>
                  <div className="tl-title">{p.title}</div>
                  <div className="tl-sub">
                    {sectionLabel(sections, p.sectionId)}
                    {p.room ? ` · Room ${p.room}` : ""}
                    {p.cover ? ` · ${p.cover}` : ""}
                  </div>
                  <div className="tl-actions">
                    <button type="button" onClick={() => router.push(`/attendance?section=${p.sectionId}`)}>
                      Mark attendance
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {day && day.coveredForThem.length > 0 && (
          <p className="hint" style={{ marginTop: 12 }}>
            {day.coveredForThem
              .map((c) => `P${c.periodNo} ${c.subjectName} is being taken by ${c.substituteStaffName}`)
              .join(" · ")}
          </p>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

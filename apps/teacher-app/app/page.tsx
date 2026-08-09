"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  getSession,
  listSections,
  SectionDto,
  Session,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

function todayDow(): number {
  return new Date().getDay(); // 0 = Sunday .. 6 = Saturday
}

function nowHHMM(): string {
  const d = new Date();
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") + ":00";
}

function sectionLabel(sections: SectionDto[] | null, sectionId: string): string {
  const s = sections?.find((x) => x.id === sectionId);
  return s ? `${s.gradeName}-${s.code}` : "Section";
}

export default function TodayPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [slots, setSlots] = useState<TimetableSlotDto[] | null>(null);
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
    Promise.all([timetableForTeacher(s.subjectId), listSections(s.schoolId)])
      .then(([tt, secs]) => {
        setSlots(tt);
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

  const today = slots?.filter((s) => s.dayOfWeek === todayDow()).sort((a, b) => a.periodNo - b.periodNo) ?? null;
  const now = nowHHMM();

  return (
    <main className="shell">
      <div className="panel">
        <h2>Today&apos;s schedule</h2>
        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}
        {today && today.length === 0 && <p className="empty-note">No periods scheduled for you today.</p>}
        {today && today.length > 0 && (
          <div className="timeline" style={{ marginTop: 12 }}>
            {today.map((slot) => {
              const isNow = now >= slot.startsAt && now < slot.endsAt;
              return (
                <div className={"tl-item" + (isNow ? " now" : "")} key={slot.id}>
                  <div className="tl-time">
                    P{slot.periodNo} · {slot.startsAt.slice(0, 5)}–{slot.endsAt.slice(0, 5)}
                  </div>
                  <div className="tl-title">{slot.subjectName}</div>
                  <div className="tl-sub">
                    {sectionLabel(sections, slot.sectionId)}
                    {slot.room ? ` · Room ${slot.room}` : ""}
                  </div>
                  <div className="tl-actions">
                    <button type="button" onClick={() => router.push(`/attendance?section=${slot.sectionId}`)}>
                      Mark attendance
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

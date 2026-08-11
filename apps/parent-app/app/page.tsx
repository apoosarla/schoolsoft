"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AnnouncementDto,
  ApiError,
  AttendanceRecordDto,
  attendanceForSectionOnDate,
  getSession,
  listAnnouncements,
  Session,
  StudentDto,
  studentsOfGuardian,
} from "@/lib/api";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function studentName(s: StudentDto): string {
  return `${s.firstName} ${s.lastName ?? ""}`.trim();
}

export default function HomePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [attendance, setAttendance] = useState<AttendanceRecordDto[] | null>(null);
  const [announcements, setAnnouncements] = useState<AnnouncementDto[] | null>(null);
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
    Promise.all([studentsOfGuardian(s.subjectId), listAnnouncements(s.schoolId)])
      .then(([kids, ann]) => {
        setChildren(kids);
        if (kids.length > 0) setActiveId(kids[0].id);
        setAnnouncements(
          ann
            .filter((a) => a.publishedAt)
            .sort((a, b) => (b.publishedAt ?? "").localeCompare(a.publishedAt ?? ""))
            .slice(0, 3)
        );
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [router]);

  const active = children?.find((c) => c.id === activeId) ?? null;

  useEffect(() => {
    if (!active || !active.currentSectionId) {
      setAttendance(null);
      return;
    }
    attendanceForSectionOnDate(active.currentSectionId, todayIso())
      .then(setAttendance)
      .catch((err) => setError(describeError(err)));
  }, [active]);

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Home</h2>
          <p className="hint">This account isn&apos;t linked to a guardian record.</p>
        </div>
      </main>
    );
  }

  const myAttendance = active ? attendance?.find((a) => a.studentId === active.id && a.periodNo == null) : null;

  return (
    <main className="shell">
      {loading && (
        <div className="panel">
          <p className="hint">Loading…</p>
        </div>
      )}
      {error && <div className="error-banner">{error}</div>}

      {children && children.length === 0 && (
        <div className="panel">
          <h2>Home</h2>
          <p className="empty-note">No children linked to this account yet.</p>
        </div>
      )}

      {children && children.length > 1 && (
        <div className="chip-row">
          {children.map((c) => (
            <button
              key={c.id}
              type="button"
              className={"chip-btn" + (c.id === activeId ? " active" : "")}
              onClick={() => setActiveId(c.id)}
            >
              {c.firstName}
            </button>
          ))}
        </div>
      )}

      <div className="grid-2">
        {active && (
          <div className="panel">
            <h2>{studentName(active)}</h2>
            <p className="hint">
              {active.admissionNo}
              {active.currentSectionLabel ? ` · ${active.currentSectionLabel}` : ""}
            </p>
            <div className="stat-row" style={{ marginTop: 12 }}>
              <div className="stat-chip">
                <span className="n">
                  {myAttendance ? myAttendance.status.replace("_", " ") : active.currentSectionId ? "—" : "N/A"}
                </span>
                <span className="l">Today&apos;s attendance</span>
              </div>
              <div className="stat-chip">
                <span className="n">{active.status}</span>
                <span className="l">Enrolment</span>
              </div>
            </div>
          </div>
        )}

        {announcements && announcements.length > 0 && (
          <div className="panel">
            <h2>Announcements</h2>
            <div className="timeline" style={{ marginTop: 12 }}>
              {announcements.map((a) => (
                <div className="tl-item" key={a.id}>
                  <div className="tl-time">{a.publishedAt?.slice(0, 10)}</div>
                  <div className="tl-title">{a.title}</div>
                  <div className="tl-sub">{a.body}</div>
                </div>
              ))}
            </div>
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

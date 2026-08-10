"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AnnouncementDto,
  announcementsForSchool,
  ApiError,
  createAnnouncement,
  getSession,
  listSections,
  publishAnnouncement,
  SectionDto,
  Session,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

export default function CommsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [mySections, setMySections] = useState<SectionDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [announcements, setAnnouncements] = useState<AnnouncementDto[] | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [posting, setPosting] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;

    Promise.all([timetableForTeacher(s.subjectId), listSections(s.schoolId), announcementsForSchool(s.schoolId)])
      .then(([tt, allSections, ann]: [TimetableSlotDto[], SectionDto[], AnnouncementDto[]]) => {
        const ids = Array.from(new Set(tt.map((t) => t.sectionId)));
        const mine = allSections.filter((sec) => ids.includes(sec.id));
        setMySections(mine);
        if (mine.length > 0) setSectionId(mine[0].id);
        setAnnouncements(
          ann
            .filter((a) => a.scopeType === "school" || (a.scopeIds ?? []).some((id) => ids.includes(id)))
            .sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))
        );
      })
      .catch((err) => setError(describeError(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  async function onPost(e: React.FormEvent) {
    e.preventDefault();
    if (!session || !sectionId) return;
    setPosting(true);
    setError(null);
    try {
      const created = await createAnnouncement({
        schoolId: session.schoolId,
        scopeType: "section",
        scopeIds: [sectionId],
        title: title.trim(),
        body: body.trim(),
        channels: ["app"],
        createdByUserId: session.userAccountId,
      });
      const published = await publishAnnouncement(created.id);
      setTitle("");
      setBody("");
      setAnnouncements((prev) => [published, ...(prev ?? [])]);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPosting(false);
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Comms</h2>
          <p className="hint">This account isn&apos;t linked to a staff record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Post to a class</h2>
        <form onSubmit={onPost}>
          <div className="form-row" style={{ flexDirection: "column" }}>
            <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!mySections}>
              {mySections?.length === 0 && <option value="">No sections assigned</option>}
              {mySections?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.gradeName}-{s.code}
                </option>
              ))}
            </select>
            <input placeholder="Title" value={title} onChange={(e) => setTitle(e.target.value)} required disabled={posting} />
            <textarea
              placeholder="Message"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              required
              disabled={posting}
              rows={3}
            />
            <button type="submit" disabled={posting || !sectionId}>
              {posting ? "Posting…" : "Post announcement"}
            </button>
          </div>
        </form>
        {error && <div className="error-banner">{error}</div>}
      </div>

      <div className="panel">
        <h2>Recent announcements</h2>
        {!announcements && <p className="hint">Loading…</p>}
        {announcements && announcements.length === 0 && <p className="empty-note">Nothing posted yet.</p>}
        {announcements && announcements.length > 0 && (
          <div className="timeline" style={{ marginTop: 12 }}>
            {announcements.map((a) => (
              <div className="tl-item" key={a.id}>
                <div className="tl-time">{a.publishedAt?.slice(0, 10) ?? "draft"}</div>
                <div className="tl-title">{a.title}</div>
                <div className="tl-sub">{a.body}</div>
              </div>
            ))}
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

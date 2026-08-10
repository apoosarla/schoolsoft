"use client";

import { Fragment, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  createThread,
  getSession,
  MessageDto,
  MessageThreadDto,
  messagesForThread,
  SectionTeacherDto,
  sendMessage,
  Session,
  StudentDto,
  studentsOfGuardian,
  staffDirectory,
  teachersForSection,
  threadsForUser,
  UserDirectoryEntryDto,
} from "@/lib/api";

export default function MessagesPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [teachers, setTeachers] = useState<SectionTeacherDto[] | null>(null);
  const [directory, setDirectory] = useState<UserDirectoryEntryDto[] | null>(null);
  const [threads, setThreads] = useState<MessageThreadDto[] | null>(null);
  const [openThreadId, setOpenThreadId] = useState<string | null>(null);
  const [messages, setMessages] = useState<MessageDto[] | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;
    Promise.all([studentsOfGuardian(s.subjectId), threadsForUser(s.userAccountId), staffDirectory(s.schoolId)])
      .then(([kids, th, dir]) => {
        setChildren(kids);
        if (kids.length > 0) setActiveId(kids[0].id);
        setThreads(th.slice().sort((a, b) => (b.lastMessageAt ?? "").localeCompare(a.lastMessageAt ?? "")));
        setDirectory(dir);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const active = children?.find((c) => c.id === activeId) ?? null;

  useEffect(() => {
    if (!active?.currentSectionId) {
      setTeachers(null);
      return;
    }
    teachersForSection(active.currentSectionId).then(setTeachers).catch((err) => setError(describeError(err)));
  }, [active]);

  function directoryFor(staffId: string): UserDirectoryEntryDto | undefined {
    return directory?.find((d) => d.subjectType === "staff" && d.subjectId === staffId);
  }

  async function onStartThread(teacherUserId: string) {
    if (!session || !active) return;
    setStarting(true);
    setError(null);
    try {
      const existing = threads?.find(
        (t) => t.subjectStudentId === active.id && t.participants.includes(teacherUserId)
      );
      const thread =
        existing ??
        (await createThread({
          schoolId: session.schoolId,
          subjectStudentId: active.id,
          participants: [session.userAccountId, teacherUserId],
        }));
      setThreads((prev) => (prev?.some((t) => t.id === thread.id) ? prev : [thread, ...(prev ?? [])]));
      openThread(thread.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setStarting(false);
    }
  }

  async function openThread(id: string) {
    setOpenThreadId(id);
    setError(null);
    try {
      const msgs = await messagesForThread(id);
      setMessages(msgs);
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onSend() {
    if (!session || !openThreadId || !draft.trim()) return;
    setSending(true);
    setError(null);
    try {
      const msg = await sendMessage(openThreadId, session.userAccountId, draft.trim());
      setMessages((prev) => [...(prev ?? []), msg]);
      setDraft("");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSending(false);
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Messages</h2>
          <p className="hint">This account isn&apos;t linked to a guardian record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}

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

      {teachers && teachers.length > 0 && (
        <div className="panel">
          <h2>{active?.firstName}&apos;s teachers</h2>
          <div className="chip-row" style={{ marginTop: 8, marginBottom: 0 }}>
            {teachers.map((t) => {
              const dir = directoryFor(t.teacherStaffId);
              if (!dir) return null;
              return (
                <button key={t.id} type="button" className="chip-btn" disabled={starting} onClick={() => onStartThread(dir.userAccountId)}>
                  {t.teacherName} · {t.subjectName}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <div className="panel">
        <h2>Conversations</h2>
        {!threads && <p className="hint">Loading…</p>}
        {threads && threads.length === 0 && <p className="empty-note">No conversations yet. Message a teacher above.</p>}
        {threads?.map((t) => (
          <Fragment key={t.id}>
            <button type="button" className="panel-btn" onClick={() => openThread(t.id)}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <span>Thread {t.id.slice(0, 8)}</span>
                <span className="list-row-sub">{t.lastMessageAt?.slice(0, 16).replace("T", " ") ?? "—"}</span>
              </div>
            </button>
            {openThreadId === t.id && (
              <div style={{ marginTop: -8, marginBottom: 10 }}>
                <div className="timeline" style={{ marginTop: 12 }}>
                  {messages?.length === 0 && <p className="hint">No messages yet.</p>}
                  {messages?.map((m) => (
                    <div className="tl-item" key={m.id}>
                      <div className="tl-time">{m.sentAt.slice(0, 16).replace("T", " ")}</div>
                      <div className="tl-sub">{m.body}</div>
                    </div>
                  ))}
                </div>
                <div className="form-row" style={{ marginTop: 10 }}>
                  <input
                    placeholder="Write a message…"
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    style={{ flex: 1 }}
                  />
                  <button type="button" onClick={onSend} disabled={sending || !draft.trim()}>
                    {sending ? "Sending…" : "Send"}
                  </button>
                </div>
              </div>
            )}
          </Fragment>
        ))}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

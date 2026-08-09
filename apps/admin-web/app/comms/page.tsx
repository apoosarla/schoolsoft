"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ANNOUNCEMENT_SCOPES,
  AnnouncementDto,
  ApiError,
  createAnnouncement,
  createThread,
  getSession,
  listAnnouncements,
  listDirectory,
  listMessages,
  listThreads,
  MessageDto,
  MessageThreadDto,
  publishAnnouncement,
  sendMessage,
  Session,
  UserDirectoryEntryDto,
} from "@/lib/api";

const emptyForm = { scopeType: "school", title: "", body: "", channels: ["push", "email"] as string[] };
const CHANNELS = ["push", "email", "sms"];

export default function CommsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [announcements, setAnnouncements] = useState<AnnouncementDto[] | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [publishingId, setPublishingId] = useState<string | null>(null);

  const [threads, setThreads] = useState<MessageThreadDto[] | null>(null);
  const [selectedThread, setSelectedThread] = useState<MessageThreadDto | null>(null);
  const [messages, setMessages] = useState<MessageDto[] | null>(null);
  const [replyBody, setReplyBody] = useState("");
  const [sending, setSending] = useState(false);

  const [showNewThread, setShowNewThread] = useState(false);
  const [directoryQ, setDirectoryQ] = useState("");
  const [directoryResults, setDirectoryResults] = useState<UserDirectoryEntryDto[] | null>(null);
  const [participants, setParticipants] = useState<UserDirectoryEntryDto[]>([]);
  const [creatingThread, setCreatingThread] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    refreshAnnouncements(s.schoolId);
    listThreads(s.userAccountId)
      .then(setThreads)
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refreshAnnouncements(schoolId: string) {
    listAnnouncements(schoolId)
      .then(setAnnouncements)
      .catch((err) => setError(describeError(err)));
  }

  async function onCreate() {
    if (!session) return;
    setCreating(true);
    setError(null);
    try {
      await createAnnouncement({
        schoolId: session.schoolId,
        scopeType: form.scopeType,
        title: form.title,
        body: form.body,
        channels: form.channels,
        createdByUserId: session.userAccountId,
      });
      setForm(emptyForm);
      setShowForm(false);
      refreshAnnouncements(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onPublish(a: AnnouncementDto) {
    setPublishingId(a.id);
    setError(null);
    try {
      await publishAnnouncement(a.id);
      if (session) refreshAnnouncements(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPublishingId(null);
    }
  }

  function toggleChannel(ch: string) {
    setForm((f) => ({
      ...f,
      channels: f.channels.includes(ch) ? f.channels.filter((c) => c !== ch) : [...f.channels, ch],
    }));
  }

  useEffect(() => {
    if (!session || !directoryQ) {
      setDirectoryResults(null);
      return;
    }
    listDirectory(session.schoolId, directoryQ)
      .then(setDirectoryResults)
      .catch((err) => setError(describeError(err)));
  }, [session, directoryQ]);

  function addParticipant(u: UserDirectoryEntryDto) {
    if (!participants.some((p) => p.userAccountId === u.userAccountId)) {
      setParticipants((p) => [...p, u]);
    }
    setDirectoryQ("");
    setDirectoryResults(null);
  }

  function removeParticipant(userAccountId: string) {
    setParticipants((p) => p.filter((x) => x.userAccountId !== userAccountId));
  }

  async function onCreateThread() {
    if (!session || participants.length === 0) return;
    setCreatingThread(true);
    setError(null);
    try {
      const thread = await createThread({
        schoolId: session.schoolId,
        participants: [session.userAccountId, ...participants.map((p) => p.userAccountId)],
      });
      setParticipants([]);
      setShowNewThread(false);
      setThreads(await listThreads(session.userAccountId));
      selectThread(thread);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingThread(false);
    }
  }

  function selectThread(t: MessageThreadDto) {
    setSelectedThread(t);
    setError(null);
    listMessages(t.id)
      .then(setMessages)
      .catch((err) => setError(describeError(err)));
  }

  async function onSend() {
    if (!session || !selectedThread || !replyBody.trim()) return;
    setSending(true);
    setError(null);
    try {
      await sendMessage(selectedThread.id, session.userAccountId, replyBody);
      setReplyBody("");
      setMessages(await listMessages(selectedThread.id));
      setThreads(await listThreads(session.userAccountId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSending(false);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Announcements</h2>
          <button type="button" onClick={() => setShowForm((v) => !v)}>
            {showForm ? "Cancel" : "New announcement"}
          </button>
        </div>

        {showForm && (
          <div>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <select value={form.scopeType} onChange={(e) => setForm((f) => ({ ...f, scopeType: e.target.value }))}>
                {ANNOUNCEMENT_SCOPES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
              <input
                placeholder="Title"
                value={form.title}
                onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))}
                style={{ minWidth: 240 }}
              />
            </div>
            <div className="form-row">
              <textarea
                placeholder="Body"
                value={form.body}
                onChange={(e) => setForm((f) => ({ ...f, body: e.target.value }))}
                style={{ minWidth: 400, minHeight: 80 }}
              />
            </div>
            <div className="form-row">
              {CHANNELS.map((ch) => (
                <label key={ch} className="form-row" style={{ alignItems: "center", gap: 4 }}>
                  <input type="checkbox" checked={form.channels.includes(ch)} onChange={() => toggleChannel(ch)} />
                  {ch}
                </label>
              ))}
              <button type="button" onClick={onCreate} disabled={creating || !form.title || !form.body}>
                {creating ? "Creating…" : "Create announcement"}
              </button>
            </div>
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}
        {announcements && announcements.length === 0 && <p className="hint">No announcements yet.</p>}
        {announcements && announcements.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Title</th>
                <th>Scope</th>
                <th>Channels</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {announcements.map((a) => (
                <tr key={a.id}>
                  <td>{a.title}</td>
                  <td>{a.scopeType}</td>
                  <td>{a.channels.join(", ")}</td>
                  <td>
                    <span className={`badge ${a.publishedAt ? "badge-active" : ""}`}>
                      {a.publishedAt ? "published" : "draft"}
                    </span>
                  </td>
                  <td>
                    {!a.publishedAt && (
                      <button type="button" onClick={() => onPublish(a)} disabled={publishingId === a.id}>
                        {publishingId === a.id ? "…" : "Publish"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Messages</h2>
          <button type="button" onClick={() => setShowNewThread((v) => !v)}>
            {showNewThread ? "Cancel" : "New thread"}
          </button>
        </div>

        {showNewThread && (
          <div className="form-row" style={{ flexWrap: "wrap", alignItems: "flex-start" }}>
            <div>
              <input
                placeholder="Search staff or guardians"
                value={directoryQ}
                onChange={(e) => setDirectoryQ(e.target.value)}
                style={{ minWidth: 240 }}
              />
              {directoryResults && directoryResults.length > 0 && (
                <table>
                  <tbody>
                    {directoryResults.map((u) => (
                      <tr key={u.userAccountId} style={{ cursor: "pointer" }} onClick={() => addParticipant(u)}>
                        <td>{u.displayName}</td>
                        <td className="hint">{u.subjectType}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div>
              {participants.length === 0 && <p className="hint">No participants added yet.</p>}
              {participants.map((p) => (
                <div key={p.userAccountId} className="form-row" style={{ alignItems: "center", gap: 4 }}>
                  <span>
                    {p.displayName} <span className="hint">({p.subjectType})</span>
                  </span>
                  <button type="button" onClick={() => removeParticipant(p.userAccountId)}>
                    Remove
                  </button>
                </div>
              ))}
              <button type="button" onClick={onCreateThread} disabled={creatingThread || participants.length === 0}>
                {creatingThread ? "Creating…" : "Start thread"}
              </button>
            </div>
          </div>
        )}

        {threads && threads.length === 0 && <p className="hint">No message threads for this account yet.</p>}
        {threads && threads.length > 0 && (
          <div className="form-row" style={{ alignItems: "flex-start", gap: 16 }}>
            <table style={{ maxWidth: 320 }}>
              <thead>
                <tr>
                  <th>Thread</th>
                  <th>Last activity</th>
                </tr>
              </thead>
              <tbody>
                {threads.map((t) => (
                  <tr key={t.id} style={{ cursor: "pointer" }} onClick={() => selectThread(t)}>
                    <td>{t.id.slice(0, 8)}</td>
                    <td>{t.lastMessageAt ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            {selectedThread && (
              <div style={{ flex: 1 }}>
                {messages && messages.length === 0 && <p className="hint">No messages yet.</p>}
                {messages && messages.length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    {messages.map((m) => (
                      <div key={m.id} style={{ padding: "4px 0" }}>
                        <strong>{m.senderUserId === session.userAccountId ? "You" : m.senderUserId.slice(0, 8)}:</strong>{" "}
                        {m.body}
                        <span className="hint"> — {m.sentAt}</span>
                      </div>
                    ))}
                  </div>
                )}
                <div className="form-row">
                  <input
                    placeholder="Reply…"
                    value={replyBody}
                    onChange={(e) => setReplyBody(e.target.value)}
                    style={{ minWidth: 300 }}
                  />
                  <button type="button" onClick={onSend} disabled={sending || !replyBody.trim()}>
                    {sending ? "Sending…" : "Send"}
                  </button>
                </div>
              </div>
            )}
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

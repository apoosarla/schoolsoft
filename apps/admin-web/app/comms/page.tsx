"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ANNOUNCEMENT_SCOPES,
  AnnouncementDto,
  ApiError,
  createAnnouncement,
  getSession,
  listAnnouncements,
  listMessages,
  listThreads,
  MessageDto,
  MessageThreadDto,
  publishAnnouncement,
  sendMessage,
  Session,
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
        <h2>Messages</h2>
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

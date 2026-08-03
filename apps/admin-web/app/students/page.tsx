"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, getSession, listStudents, Session, StudentDto } from "@/lib/api";

export default function StudentsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [q, setQ] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
  }, [router]);

  useEffect(() => {
    if (!session) return;
    setLoading(true);
    setError(null);
    listStudents(session.schoolId, q || undefined)
      .then(setStudents)
      .catch((err) => setError(err instanceof ApiError ? `${err.code}: ${err.message}` : "Failed to load"))
      .finally(() => setLoading(false));
  }, [session, q]);

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <h2>Students</h2>
        <div className="form-row">
          <input
            placeholder="Search by name or admission no."
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ minWidth: 280 }}
          />
        </div>
        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}
        {students && students.length === 0 && <p className="hint">No students found.</p>}
        {students && students.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Admission no.</th>
                <th>Name</th>
                <th>Section</th>
                <th>Roll no.</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {students.map((s) => (
                <tr key={s.id}>
                  <td>{s.admissionNo}</td>
                  <td>
                    {s.firstName} {s.lastName ?? ""}
                  </td>
                  <td>{s.currentSectionLabel ?? "—"}</td>
                  <td>{s.rollNo ?? "—"}</td>
                  <td>
                    <span className={`badge ${s.status === "active" ? "badge-active" : "badge-suspended"}`}>
                      {s.status}
                    </span>
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

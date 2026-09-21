"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  activeLibraryIssuesForMember,
  addLibraryCopy,
  ApiError,
  createLibraryTitle,
  getSession,
  hasScreen,
  issueLibraryCopy,
  LibraryCopyDto,
  LibraryIssueDto,
  LibraryTitleDto,
  listLibraryCopies,
  listLibraryTitles,
  listStudents,
  returnLibraryCopy,
  Session,
  StudentDto,
} from "@/lib/api";

function todayPlusDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

const emptyTitleForm = { isbn: "", title: "", author: "", publisher: "", year: "" };

export default function LibraryPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [titleQ, setTitleQ] = useState("");
  const [titles, setTitles] = useState<LibraryTitleDto[] | null>(null);
  const [showTitleForm, setShowTitleForm] = useState(false);
  const [titleForm, setTitleForm] = useState(emptyTitleForm);
  const [creatingTitle, setCreatingTitle] = useState(false);

  const [selectedTitle, setSelectedTitle] = useState<LibraryTitleDto | null>(null);
  const [copies, setCopies] = useState<LibraryCopyDto[] | null>(null);
  const [barcode, setBarcode] = useState("");
  const [addingCopy, setAddingCopy] = useState(false);

  const [issueCopy, setIssueCopy] = useState<LibraryCopyDto | null>(null);
  const [studentQ, setStudentQ] = useState("");
  const [studentResults, setStudentResults] = useState<StudentDto[] | null>(null);
  const [issueStudent, setIssueStudent] = useState<StudentDto | null>(null);
  const [dueOn, setDueOn] = useState(todayPlusDays(14));
  const [issuing, setIssuing] = useState(false);

  const [loanStudentQ, setLoanStudentQ] = useState("");
  const [loanStudentResults, setLoanStudentResults] = useState<StudentDto[] | null>(null);
  const [loanStudent, setLoanStudent] = useState<StudentDto | null>(null);
  const [loans, setLoans] = useState<LibraryIssueDto[] | null>(null);
  const [returningId, setReturningId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "library")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    listLibraryTitles(s.schoolId)
      .then(setTitles)
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refreshTitles(schoolId: string, q: string) {
    listLibraryTitles(schoolId, q || undefined)
      .then(setTitles)
      .catch((err) => setError(describeError(err)));
  }

  useEffect(() => {
    if (!session) return;
    refreshTitles(session.schoolId, titleQ);
  }, [session, titleQ]);

  async function onCreateTitle() {
    if (!session) return;
    setCreatingTitle(true);
    setError(null);
    try {
      await createLibraryTitle(session.schoolId, {
        isbn: titleForm.isbn || undefined,
        title: titleForm.title,
        author: titleForm.author || undefined,
        publisher: titleForm.publisher || undefined,
        year: titleForm.year ? Number(titleForm.year) : undefined,
      });
      setTitleForm(emptyTitleForm);
      setShowTitleForm(false);
      refreshTitles(session.schoolId, titleQ);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingTitle(false);
    }
  }

  function selectTitle(t: LibraryTitleDto) {
    setSelectedTitle(t);
    setIssueCopy(null);
    setError(null);
    listLibraryCopies(t.id)
      .then(setCopies)
      .catch((err) => setError(describeError(err)));
  }

  async function onAddCopy() {
    if (!selectedTitle) return;
    setAddingCopy(true);
    setError(null);
    try {
      await addLibraryCopy(selectedTitle.id, barcode);
      setBarcode("");
      setCopies(await listLibraryCopies(selectedTitle.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setAddingCopy(false);
    }
  }

  useEffect(() => {
    if (!session || !studentQ) {
      setStudentResults(null);
      return;
    }
    listStudents(session.schoolId, studentQ)
      .then(setStudentResults)
      .catch((err) => setError(describeError(err)));
  }, [session, studentQ]);

  async function onIssue() {
    if (!session || !selectedTitle || !issueCopy || !issueStudent) return;
    setIssuing(true);
    setError(null);
    try {
      await issueLibraryCopy({
        schoolId: session.schoolId,
        copyId: issueCopy.id,
        memberType: "student",
        memberId: issueStudent.id,
        dueOn,
      });
      setIssueCopy(null);
      setIssueStudent(null);
      setStudentQ("");
      setStudentResults(null);
      setCopies(await listLibraryCopies(selectedTitle.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setIssuing(false);
    }
  }

  useEffect(() => {
    if (!session || !loanStudentQ) {
      setLoanStudentResults(null);
      return;
    }
    listStudents(session.schoolId, loanStudentQ)
      .then(setLoanStudentResults)
      .catch((err) => setError(describeError(err)));
  }, [session, loanStudentQ]);

  function selectLoanStudent(s: StudentDto) {
    setLoanStudent(s);
    setLoanStudentResults(null);
    setLoanStudentQ("");
    refreshLoans(s.id);
  }

  function refreshLoans(studentId: string) {
    setError(null);
    activeLibraryIssuesForMember("student", studentId)
      .then(setLoans)
      .catch((err) => setError(describeError(err)));
  }

  async function onReturn(issue: LibraryIssueDto) {
    if (!loanStudent) return;
    setReturningId(issue.id);
    setError(null);
    try {
      await returnLibraryCopy(issue.id);
      refreshLoans(loanStudent.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setReturningId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Library — Catalogue</h2>
          <button type="button" onClick={() => setShowTitleForm((v) => !v)}>
            {showTitleForm ? "Cancel" : "New title"}
          </button>
        </div>
        <div className="form-row">
          <input
            placeholder="Search titles"
            value={titleQ}
            onChange={(e) => setTitleQ(e.target.value)}
            style={{ minWidth: 260 }}
          />
        </div>

        {showTitleForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <input
              placeholder="Title"
              value={titleForm.title}
              onChange={(e) => setTitleForm((f) => ({ ...f, title: e.target.value }))}
            />
            <input
              placeholder="Author"
              value={titleForm.author}
              onChange={(e) => setTitleForm((f) => ({ ...f, author: e.target.value }))}
            />
            <input
              placeholder="Publisher"
              value={titleForm.publisher}
              onChange={(e) => setTitleForm((f) => ({ ...f, publisher: e.target.value }))}
            />
            <input
              placeholder="Year"
              type="number"
              value={titleForm.year}
              onChange={(e) => setTitleForm((f) => ({ ...f, year: e.target.value }))}
              style={{ maxWidth: 100 }}
            />
            <input
              placeholder="ISBN"
              value={titleForm.isbn}
              onChange={(e) => setTitleForm((f) => ({ ...f, isbn: e.target.value }))}
              style={{ maxWidth: 160 }}
            />
            <button type="button" onClick={onCreateTitle} disabled={creatingTitle || !titleForm.title}>
              {creatingTitle ? "Creating…" : "Create title"}
            </button>
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}
        {titles && titles.length === 0 && <p className="hint">No titles found.</p>}
        {titles && titles.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Title</th>
                <th>Author</th>
                <th>Publisher</th>
                <th>Year</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {titles.map((t) => (
                <tr key={t.id} style={{ cursor: "pointer" }} onClick={() => selectTitle(t)}>
                  <td>{t.title}</td>
                  <td>{t.author ?? "—"}</td>
                  <td>{t.publisher ?? "—"}</td>
                  <td>{t.year ?? "—"}</td>
                  <td>
                    <button type="button" onClick={() => selectTitle(t)}>
                      {selectedTitle?.id === t.id ? "Selected" : "Open"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selectedTitle && (
        <div className="panel">
          <h2>Copies — {selectedTitle.title}</h2>
          <div className="form-row">
            <input
              placeholder="Barcode"
              value={barcode}
              onChange={(e) => setBarcode(e.target.value)}
              style={{ maxWidth: 160 }}
            />
            <button type="button" onClick={onAddCopy} disabled={addingCopy || !barcode}>
              {addingCopy ? "Adding…" : "Add copy"}
            </button>
          </div>

          {copies && copies.length === 0 && <p className="hint">No copies yet.</p>}
          {copies && copies.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Barcode</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {copies.map((c) => (
                  <tr key={c.id}>
                    <td>{c.barcode}</td>
                    <td>
                      <span className={`badge ${c.status === "available" ? "badge-active" : ""}`}>{c.status}</span>
                    </td>
                    <td>
                      {c.status === "available" && (
                        <button type="button" onClick={() => setIssueCopy(c)}>
                          {issueCopy?.id === c.id ? "Issuing…" : "Issue"}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {issueCopy && (
            <div className="form-row" style={{ flexWrap: "wrap", marginTop: 8 }}>
              <strong>Issue {issueCopy.barcode} to:</strong>
              <input
                placeholder="Search student"
                value={issueStudent ? `${issueStudent.firstName} ${issueStudent.lastName ?? ""}` : studentQ}
                onChange={(e) => {
                  setIssueStudent(null);
                  setStudentQ(e.target.value);
                }}
                style={{ minWidth: 220 }}
              />
              <input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />
              <button type="button" onClick={onIssue} disabled={issuing || !issueStudent}>
                {issuing ? "Issuing…" : "Confirm issue"}
              </button>
              <button type="button" onClick={() => setIssueCopy(null)}>
                Cancel
              </button>
              {studentResults && studentResults.length > 0 && !issueStudent && (
                <table>
                  <tbody>
                    {studentResults.map((s) => (
                      <tr key={s.id} style={{ cursor: "pointer" }} onClick={() => setIssueStudent(s)}>
                        <td>{s.admissionNo}</td>
                        <td>
                          {s.firstName} {s.lastName ?? ""}
                        </td>
                        <td>{s.currentSectionLabel ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </div>
      )}

      <div className="panel">
        <h2>Active loans</h2>
        <div className="form-row">
          <input
            placeholder="Search student by name or admission no."
            value={loanStudentQ}
            onChange={(e) => setLoanStudentQ(e.target.value)}
            style={{ minWidth: 280 }}
          />
        </div>
        {loanStudentResults && loanStudentResults.length > 0 && (
          <table>
            <tbody>
              {loanStudentResults.map((s) => (
                <tr key={s.id} style={{ cursor: "pointer" }} onClick={() => selectLoanStudent(s)}>
                  <td>{s.admissionNo}</td>
                  <td>
                    {s.firstName} {s.lastName ?? ""}
                  </td>
                  <td>{s.currentSectionLabel ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {loanStudent && (
          <>
            <p className="hint">
              Loans for {loanStudent.firstName} {loanStudent.lastName ?? ""} ({loanStudent.admissionNo})
            </p>
            {loans && loans.length === 0 && <p className="hint">No active loans.</p>}
            {loans && loans.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Issued</th>
                    <th>Due</th>
                    <th>Fine</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {loans.map((l) => (
                    <tr key={l.id}>
                      <td>{l.issuedOn}</td>
                      <td>{l.dueOn}</td>
                      <td>{inr(l.fine)}</td>
                      <td>
                        <button type="button" onClick={() => onReturn(l)} disabled={returningId === l.id}>
                          {returningId === l.id ? "…" : "Return"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}

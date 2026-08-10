"use client";

import { useEffect, useState } from "react";
import { apply, ApiError, GradeDto, listGrades } from "@/lib/api";

export default function ApplyPage() {
  const [grades, setGrades] = useState<GradeDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [applicationNo, setApplicationNo] = useState<string | null>(null);

  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [dob, setDob] = useState("");
  const [gender, setGender] = useState("");
  const [gradeId, setGradeId] = useState("");
  const [guardianName, setGuardianName] = useState("");
  const [guardianPhone, setGuardianPhone] = useState("");
  const [guardianEmail, setGuardianEmail] = useState("");

  useEffect(() => {
    listGrades()
      .then((g) => {
        setGrades(g);
        if (g.length > 0) setGradeId(g[0].id);
      })
      .catch((err) => setError(describeError(err)));
  }, []);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res = await apply({
        applicantFirstName: firstName.trim(),
        applicantLastName: lastName.trim() || undefined,
        applicantDob: dob || undefined,
        applicantGender: gender || undefined,
        gradeId,
        guardianName: guardianName.trim(),
        guardianPhone: guardianPhone.trim(),
        guardianEmail: guardianEmail.trim() || undefined,
      });
      setApplicationNo(res.applicationNo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (applicationNo) {
    return (
      <main className="form-page">
        <div className="confirmation">
          <div className="mark">
            <svg width="24" height="24" viewBox="0 0 18 18" fill="none">
              <path d="M3.5 9.5L7 13L14.5 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h2>Application received</h2>
          <p>We&apos;ll be in touch by phone or email to schedule next steps.</p>
          <div className="app-no">{applicationNo}</div>
          <p className="hint">Keep this reference number for your records.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="form-page">
      <h1 className="serif">Apply for admission</h1>
      <p className="lede">Tell us about your child and we&apos;ll get back to you to schedule the next steps.</p>

      {error && <div className="error-banner">{error}</div>}

      <form onSubmit={onSubmit}>
        <div className="field-row">
          <div className="field-group">
            <label htmlFor="firstName">Child&apos;s first name</label>
            <input id="firstName" value={firstName} onChange={(e) => setFirstName(e.target.value)} required disabled={submitting} />
          </div>
          <div className="field-group">
            <label htmlFor="lastName">Last name</label>
            <input id="lastName" value={lastName} onChange={(e) => setLastName(e.target.value)} disabled={submitting} />
          </div>
        </div>

        <div className="field-row">
          <div className="field-group">
            <label htmlFor="dob">Date of birth</label>
            <input id="dob" type="date" value={dob} onChange={(e) => setDob(e.target.value)} disabled={submitting} />
          </div>
          <div className="field-group">
            <label htmlFor="gender">Gender</label>
            <select id="gender" value={gender} onChange={(e) => setGender(e.target.value)} disabled={submitting}>
              <option value="">Prefer not to say</option>
              <option value="male">Male</option>
              <option value="female">Female</option>
              <option value="other">Other</option>
            </select>
          </div>
        </div>

        <div className="field-group">
          <label htmlFor="grade">Applying for grade</label>
          <select id="grade" value={gradeId} onChange={(e) => setGradeId(e.target.value)} required disabled={submitting || !grades}>
            {grades?.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        </div>

        <div className="field-group">
          <label htmlFor="guardianName">Parent / guardian name</label>
          <input id="guardianName" value={guardianName} onChange={(e) => setGuardianName(e.target.value)} required disabled={submitting} />
        </div>

        <div className="field-row">
          <div className="field-group">
            <label htmlFor="guardianPhone">Phone</label>
            <input id="guardianPhone" value={guardianPhone} onChange={(e) => setGuardianPhone(e.target.value)} required disabled={submitting} />
          </div>
          <div className="field-group">
            <label htmlFor="guardianEmail">Email</label>
            <input id="guardianEmail" type="email" value={guardianEmail} onChange={(e) => setGuardianEmail(e.target.value)} disabled={submitting} />
          </div>
        </div>

        <button type="submit" className="btn btn-block" disabled={submitting || !gradeId}>
          {submitting ? "Submitting…" : "Submit application"}
        </button>
      </form>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

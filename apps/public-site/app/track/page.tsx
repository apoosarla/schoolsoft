"use client";

import { useState } from "react";
import { ApiError, ApplicationStatusDto, trackApplication } from "@/lib/api";

const STATE_LABELS: Record<string, string> = {
  lead: "Received",
  application_started: "Application started",
  document_pending: "Documents pending",
  fee_pending: "Fee pending",
  review: "Under review",
  test_scheduled: "Entrance test scheduled",
  test_done: "Entrance test completed",
  offered: "Offer extended",
  accepted: "Offer accepted",
  waitlist: "Waitlisted",
  rejected: "Not selected",
  enrolled: "Enrolled",
  lapsed: "Lapsed",
};

export default function TrackPage() {
  const [applicationNo, setApplicationNo] = useState("");
  const [guardianPhone, setGuardianPhone] = useState("");
  const [result, setResult] = useState<ApplicationStatusDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setSubmitting(true);
    try {
      const res = await trackApplication(applicationNo.trim(), guardianPhone.trim());
      setResult(res);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="form-page">
      <h1 className="serif">Track your application</h1>
      <p className="lede">Enter your application number and the phone number you applied with.</p>

      <form onSubmit={onSubmit}>
        <div className="field-group">
          <label htmlFor="applicationNo">Application number</label>
          <input
            id="applicationNo"
            value={applicationNo}
            onChange={(e) => setApplicationNo(e.target.value)}
            required
            disabled={submitting}
            placeholder="WEB-XXXXXXXX"
          />
        </div>
        <div className="field-group">
          <label htmlFor="guardianPhone">Phone number used to apply</label>
          <input
            id="guardianPhone"
            value={guardianPhone}
            onChange={(e) => setGuardianPhone(e.target.value)}
            required
            disabled={submitting}
          />
        </div>
        <button type="submit" className="btn btn-block" disabled={submitting}>
          {submitting ? "Checking…" : "Check status"}
        </button>
      </form>

      {error && <div className="error-banner" style={{ marginTop: 20 }}>{error}</div>}

      {result && (
        <div className="confirmation" style={{ marginTop: 32 }}>
          <div className="mark">
            <svg width="24" height="24" viewBox="0 0 18 18" fill="none">
              <path d="M3.5 9.5L7 13L14.5 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h2>{result.applicantFirstName} {result.applicantLastName ?? ""}</h2>
          <p>{STATE_LABELS[result.state] ?? result.state}</p>
          {result.testScore != null && <p>Test score: {result.testScore}</p>}
          {result.offerExpiresOn && <p>Offer valid until {result.offerExpiresOn}</p>}
          <div className="app-no">{result.applicationNo}</div>
          <p className="hint">Applied {result.createdAt.slice(0, 10)}</p>
        </div>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return "No application found for that number and phone. Double-check and try again.";
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, getSchool, PublicSchoolDto } from "@/lib/api";

export default function HomePage() {
  const [school, setSchool] = useState<PublicSchoolDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getSchool()
      .then(setSchool)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Unable to load school info."));
  }, []);

  return (
    <>
      <section className="hero">
        <div className="hero-eyebrow">{school?.boardCode ?? " "} Curriculum</div>
        <h1 className="hero-title serif">
          {school ? school.name : error ? "Oakridge Hyderabad" : "Loading…"}
        </h1>
        <p className="hero-sub">
          A {school ? school.boardCode : ""} school in Hyderabad focused on rigorous academics, small class sizes,
          and a genuinely happy place to grow up. Admissions for the next academic year are now open.
        </p>
        <Link href="/apply" className="btn">
          Apply now
        </Link>
        {error && <p className="hint" style={{ marginTop: 16 }}>{error}</p>}
      </section>

      <div className="highlights">
        <div className="highlight-card">
          <div className="highlight-mark">
            <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
              <path d="M9 2L15 4.3V8.4C15 12 12.4 14.8 9 16C5.6 14.8 3 12 3 8.4V4.3L9 2Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
              <path d="M6.5 9.2L8.2 10.9L11.5 7.3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h3>{school ? `${school.boardCode}-aligned` : "Rigorous curriculum"}</h3>
          <p>A structured curriculum from the primary years, taught by subject specialists.</p>
        </div>
        <div className="highlight-card">
          <div className="highlight-mark">
            <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
              <circle cx="9" cy="5.3" r="2.8" stroke="currentColor" strokeWidth="1.4" />
              <path d="M2.8 16C3.3 12.4 5.8 10.5 9 10.5C12.2 10.5 14.7 12.4 15.2 16" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
          </div>
          <h3>Small class sizes</h3>
          <p>Low student-teacher ratios so every child actually gets seen.</p>
        </div>
        <div className="highlight-card">
          <div className="highlight-mark">
            <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
              <path d="M2.5 4.8C2.5 3.8 3.3 3 4.3 3H13.7C14.7 3 15.5 3.8 15.5 4.8V10.4C15.5 11.4 14.7 12.2 13.7 12.2H7.3L4 15V12.2H4.3C3.3 12.2 2.5 11.4 2.5 10.4V4.8Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
            </svg>
          </div>
          <h3>Real communication</h3>
          <p>Parents get direct updates on attendance, grades, and fees — not a quarterly newsletter.</p>
        </div>
      </div>
    </>
  );
}

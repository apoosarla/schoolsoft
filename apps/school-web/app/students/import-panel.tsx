"use client";

import { useState } from "react";
import {
  ApiError,
  ImportBatchDto,
  ImportResultDto,
  commitStudentImport,
  previewStudentImport,
} from "@/lib/api";

const COLUMNS =
  "admission_no, first_name, middle_name, last_name, dob, gender, grade_code, " +
  "section_code, roll_no, guardian_name, guardian_relation, guardian_phone, guardian_email";

/**
 * Bringing in a register.
 *
 * The file is read here and previewed on the server, which writes nothing and
 * answers with every row it could not accept. Nothing goes in until the file
 * is clean — a register assembled out of the rows that happened to parse is
 * worse than no register, because nobody can tell afterwards which children
 * are missing from it.
 */
export default function ImportPanel({ schoolId, onImported }: { schoolId: string; onImported: () => void }) {
  const [batch, setBatch] = useState<ImportBatchDto | null>(null);
  const [result, setResult] = useState<ImportResultDto | null>(null);
  const [filename, setFilename] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onFile(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    setBatch(null);
    setFilename(file.name);
    try {
      setBatch(await previewStudentImport(schoolId, file.name, await file.text()));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function onCommit() {
    if (!batch) return;
    setBusy(true);
    setError(null);
    try {
      const done = await commitStudentImport(batch.id);
      setResult(done);
      setBatch(null);
      onImported();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  const bad = (batch?.rows ?? []).filter((r) => r.errors.length > 0);

  return (
    <div className="panel">
      <h2>Bring in a register</h2>
      <p className="hint" style={{ marginTop: 0 }}>
        A CSV with a header row. Columns: <code>{COLUMNS}</code>. Only{" "}
        <code>first_name</code>, <code>grade_code</code> and <code>section_code</code> are required —
        an admission number is issued when the file does not carry one, and two children sharing a
        guardian&apos;s phone or email are matched to one family.
      </p>

      <div className="form-row" style={{ alignItems: "center" }}>
        <input
          type="file"
          accept=".csv,text/csv"
          disabled={busy}
          onChange={(e) => onFile(e.target.files?.[0])}
        />
        {busy && <span className="hint">Working&hellip;</span>}
      </div>

      {error && <div className="error-banner">{error}</div>}

      {result && (
        <div className="notice-banner">
          Imported {result.studentsCreated} {result.studentsCreated === 1 ? "child" : "children"} onto
          the register. {result.guardiansCreated} new{" "}
          {result.guardiansCreated === 1 ? "family" : "families"}
          {result.guardiansReused > 0 && `, ${result.guardiansReused} matched to families already here`}.
        </div>
      )}

      {batch && (
        <>
          <p className="hint">
            <strong>{filename}</strong> — {batch.rowCount} {batch.rowCount === 1 ? "row" : "rows"}
            {batch.errorCount === 0
              ? ", nothing to fix."
              : `, ${batch.errorCount} to fix. Nothing is imported until they are.`}
          </p>

          {bad.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Child</th>
                  <th>What needs fixing</th>
                </tr>
              </thead>
              <tbody>
                {bad.map((r) => (
                  <tr key={r.line}>
                    <td>{r.line}</td>
                    <td>
                      {r.firstName ?? "—"} {r.lastName ?? ""}
                    </td>
                    <td>{r.errors.join("; ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="form-row" style={{ marginTop: 12, marginBottom: 0 }}>
            <button type="button" disabled={busy || !batch.canCommit} onClick={onCommit}>
              {batch.canCommit ? `Import ${batch.rowCount} children` : "Fix the file to import"}
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setBatch(null)}>
              Cancel
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You can read the register but not import into it — that needs student.import.";
    }
    // A refused commit already says how many rows are left to fix, and a
    // constraint violation names the rule it broke.
    return err.message;
  }
  return err instanceof Error ? err.message : "Unknown error";
}

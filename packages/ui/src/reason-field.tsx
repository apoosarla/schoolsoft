"use client";

import { useState } from "react";
import styles from "./reason-field.module.css";

export type ReasonFieldProps = {
  /** The question, as the field's placeholder: "Why is this card being unlocked?" */
  placeholder: string;
  /** What the confirm button says — the act, not "OK": "Unlock", "Revoke". */
  confirmLabel: string;
  /** Locks the field and both buttons while the act is in flight. */
  busy?: boolean;
  /** Called with the trimmed reason. Never called with an empty one. */
  onConfirm: (reason: string) => void;
  onCancel: () => void;
};

/**
 * Asks for the reason an audited act needs, inline, where the act was asked
 * for.
 *
 * It replaces `window.prompt`, which asked the same question in a modal the
 * page could not style, translate or test — and which a driven browser
 * answered on its own, so a reason nobody typed was written into the audit
 * log. The reason is the one column an auditor reads, so it gets a real field.
 *
 * Enter confirms and Escape cancels. The confirm button stays disabled until
 * something has been typed: an empty reason is refused by the API anyway, and
 * a button that can only fail is worse than one that waits.
 */
export function ReasonField({ placeholder, confirmLabel, busy, onConfirm, onCancel }: ReasonFieldProps) {
  const [reason, setReason] = useState("");
  const trimmed = reason.trim();

  function confirm() {
    if (trimmed && !busy) onConfirm(trimmed);
  }

  return (
    <span className={styles.field}>
      <input
        autoFocus
        className={styles.input}
        placeholder={placeholder}
        aria-label={placeholder}
        value={reason}
        disabled={busy}
        onChange={(e) => setReason(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            confirm();
          }
          if (e.key === "Escape") onCancel();
        }}
      />
      <button type="button" disabled={busy || !trimmed} onClick={confirm}>
        {confirmLabel}
      </button>
      <button type="button" className={styles.cancel} disabled={busy} onClick={onCancel}>
        Cancel
      </button>
    </span>
  );
}

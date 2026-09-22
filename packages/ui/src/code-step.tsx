"use client";

import { useEffect, useRef, useState } from "react";
import styles from "./code-step.module.css";

export type CodeStepProps = {
  /** What the code was sent to, shown back so a typo in it is visible here. */
  sentTo: string;
  /** True while a verify is in flight: the boxes lock rather than re-firing. */
  submitting: boolean;
  /** Called once, with all six digits, the moment the last one lands. */
  onSubmit: (code: string) => void;
  /** Walks back to the identifier step. */
  onBack: () => void;
  /** Overrides the standing line under the boxes. */
  note?: string;
};

/**
 * The sign-in code step, shared by every Schoolsoft surface.
 *
 * Six boxes rather than one field, so the phone offers the SMS code to
 * `one-time-code` and a pasted code lands in all six at once. Verification
 * fires on the sixth digit: there is nothing else the screen could be for,
 * and a Verify button after the last digit is a step that only ever means
 * yes.
 *
 * It lives here because five surfaces want it and a copy in each would drift:
 * the paste spread, the backspace walk and the autofill target are the kind of
 * detail that gets re-derived slightly wrong.
 */
export function CodeStep({ sentTo, submitting, onSubmit, onBack, note }: CodeStepProps) {
  const [digits, setDigits] = useState<string[]>(["", "", "", "", "", ""]);
  const boxes = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    boxes.current[0]?.focus();
  }, []);

  function place(next: string[]) {
    setDigits(next);
    const code = next.join("");
    if (code.length === 6 && !next.includes("")) onSubmit(code);
  }

  function onChange(i: number, raw: string) {
    const typed = raw.replace(/\D/g, "");
    if (!typed) {
      const next = [...digits];
      next[i] = "";
      setDigits(next);
      return;
    }
    // A paste arrives as one long value in whichever box had focus; spread it.
    const next = [...digits];
    for (let k = 0; k < typed.length && i + k < 6; k++) next[i + k] = typed[k];
    place(next);
    boxes.current[Math.min(i + typed.length, 5)]?.focus();
  }

  function onKeyDown(i: number, e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Backspace" && !digits[i] && i > 0) boxes.current[i - 1]?.focus();
    if (e.key === "ArrowLeft" && i > 0) boxes.current[i - 1]?.focus();
    if (e.key === "ArrowRight" && i < 5) boxes.current[i + 1]?.focus();
  }

  return (
    <div className={styles.step}>
      <p className={styles.lede}>
        Sent to <strong>{sentTo}</strong> ·{" "}
        <button type="button" className={styles.inline} onClick={onBack} disabled={submitting}>
          change
        </button>
      </p>
      <div className={styles.boxes}>
        {digits.map((d, i) => (
          <input
            key={i}
            ref={(el) => {
              boxes.current[i] = el;
            }}
            value={d}
            aria-label={`Digit ${i + 1}`}
            inputMode="numeric"
            autoComplete={i === 0 ? "one-time-code" : "off"}
            disabled={submitting}
            onChange={(e) => onChange(i, e.target.value)}
            onKeyDown={(e) => onKeyDown(i, e)}
          />
        ))}
      </div>
      <p className={styles.note}>
        {submitting ? "Checking…" : (note ?? "Codes last five minutes.")}
      </p>
    </div>
  );
}

/**
 * Board Integration (per design doc §1.9 — explicitly non-cuttable, one of
 * the platform's two wedge features alongside WhatsApp). Outbound adapter
 * framework for board-specific exports: CBSE's UDISE+ XML / Pariksha
 * Sangam mapping, Cambridge's CIE Direct candidate registration / syllabus
 * entries / statement-of-entry retrieval. Every export is a tracked
 * {@code board_export_job} row (queued -&gt; processing -&gt; completed/failed)
 * rather than fire-and-forget, per Risk R1's CSV-export-fallback posture —
 * a school's export never silently vanishes if a board API is degraded.
 *
 * The actual CIE Direct / UDISE+ HTTP clients are not implemented (no
 * sandbox credentials available); {@code process} synchronously completes a
 * job with a canned result so the job lifecycle and the module's contract
 * are real and testable even though the adapter body is a stub.
 */
package com.schoolsoft.boardintegration;

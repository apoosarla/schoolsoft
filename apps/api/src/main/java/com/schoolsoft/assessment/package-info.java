/**
 * Assessment &amp; Gradebook (per design doc §7 Layer 1.7 + §8). An
 * {@code Assessment} (e.g. CBSE "Periodic Test 1", Cambridge "Paper 2") owns
 * one or more {@code AssessmentComponent}s; a {@code Mark} is always on a
 * component, never the assessment directly — this is what lets CBSE and
 * Cambridge share one schema with strategy-specific shapes in
 * {@code strategy_data} JSONB. Report Cards are a separately generated
 * artefact that references assessment data plus a board-styled template.
 */
package com.schoolsoft.assessment;

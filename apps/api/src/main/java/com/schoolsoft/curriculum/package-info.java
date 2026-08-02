/**
 * Curriculum Engine (per design doc §9). Polymorphic — boards are strategies,
 * not an enum. A {@code Curriculum} is a versioned tree (Strand/Chapter →
 * Unit → Topic → LearningOutcome) with a {@code strategyCode} dispatch key;
 * assessment shape, grading scale, and export adapter all key off that code
 * downstream (Assessment / Report Cards / Board Integration modules).
 *
 * Master templates live in {@code platform.curriculum_template} (seeded with
 * CBSE + Cambridge starters); chains clone a template into their own
 * {@code chain_X.curriculum} tree, then edit the clone. Sections bind to a
 * curriculum via {@code tenancy}'s {@code section.curriculum_id}.
 */
package com.schoolsoft.curriculum;

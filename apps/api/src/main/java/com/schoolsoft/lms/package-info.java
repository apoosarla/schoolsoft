/**
 * LMS &amp; Content (per design doc §7 Layer 3). Content items and lesson plans
 * tag a {@code curriculum_node} so they're reusable across boards; homework
 * assignments and the quiz engine are the graded-work side. LTI 1.3 tool
 * registry lives here too (chain-wide by default, per-school when scoped) —
 * launch/grade-passback plumbing itself is not implemented, only the
 * registry and the {@code assignment.lti_*} linkage columns.
 */
package com.schoolsoft.lms;

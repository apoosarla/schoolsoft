/**
 * Certificates — the documents a school issues about a child's time at it: the
 * Transfer Certificate the next school will demand, the school-leaving
 * certificate and transcript a graduating cohort gets, and the bonafide
 * certificate a bank or a passport office asks for.
 *
 * <h2>What makes this its own module</h2>
 * A certificate is not a view of the record; it is a <em>statement</em> about
 * the record, made on a date, signed, and serially numbered. Its fields are
 * frozen at issue and hashed, so the document a family holds still says what it
 * said even after the marks are re-evaluated and the class is renamed. That is
 * the opposite of how every other read in this system works, which is why it
 * does not live inside {@code enrolment} or {@code assessment}: it draws from
 * both, plus attendance, and it must not be temped into recomputing from any of
 * them.
 *
 * <h2>Out of scope</h2>
 * Rendering. There is no PDF here and no template engine — the module issues the
 * <em>content</em>, numbered and verifiable, and how a school lays that out on
 * its letterhead is a printing concern that belongs with the report-card
 * renderer whenever that is built.
 *
 * <p>Also out of scope: deciding whether a child may leave. That is
 * {@code enrolment}'s withdrawal and its clearance checklist; this module
 * refuses to issue a TC until that says so, and takes no view of its own.</p>
 */
package com.schoolsoft.certificate;

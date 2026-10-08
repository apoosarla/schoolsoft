/**
 * Privacy — what the DPDP Act gives a family over the data a school holds on
 * their child: consent they can give and withdraw, a copy of everything held,
 * and removal of who the child was once the school has no reason to know.
 *
 * <h2>What makes this its own module</h2>
 * Every other module owns some of a child's data and none of them owns the
 * question "what do you hold on my child, all of it". The export answers that
 * by reading every table that hangs a row off a student, found at run time
 * rather than listed — so a table added next year is in the export by
 * existing, and nobody has to remember to come back here.
 *
 * <h2>Erasure removes the person, not the record</h2>
 * A school must keep its ledger, its register and the certificates it issued.
 * So an erasure blanks who the child and their family were and leaves the rows
 * that money, attendance and marks hang off, now belonging to nobody. It is
 * refused while the child is still on a register, because a school cannot
 * teach a child it is not allowed to know.
 *
 * <h2>Out of scope</h2>
 * Retention schedules — nothing here deletes on a timer. Staff as data
 * subjects. Correction requests: a wrong detail is fixed through the record's
 * own screen. Files in object storage. Offboarding a whole chain, which is
 * {@code DROP SCHEMA} and the platform's business.
 */
package com.schoolsoft.privacy;

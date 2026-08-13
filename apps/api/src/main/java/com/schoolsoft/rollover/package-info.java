/**
 * Year closure and rollover (design doc §7 Layer 1.2, GAP-02). Moves a school
 * from one academic year to the next: readiness, structure clone, allocation
 * of every child by their promotion decision, carry-forward of what follows
 * them, and the closure of the year behind them.
 *
 * It reads from nearly every other module and owns none of their tables — the
 * promotion decision is assessment's, the seat is tenancy's, the arrear is
 * fees' — which is why it could only be built once those existed.
 */
package com.schoolsoft.rollover;

/**
 * Fee Management, Payments, and the double-entry shadow Ledger (per design
 * doc §7 Layer 4 + §14). Invoices bill a payor for a billing cycle; a
 * {@code Payment} is the cash event (idempotent on {@code idempotency_key} so
 * gateway webhook retries never double-post); every payment writes balanced
 * {@code LedgerEntry} rows (Bank DR / Fee Receivable CR). GST e-invoice (IRN)
 * and Tally/Zoho sync are out of scope here — {@code fee_invoice.irn} fields
 * exist in the schema for that future integration.
 */
package com.schoolsoft.fees;

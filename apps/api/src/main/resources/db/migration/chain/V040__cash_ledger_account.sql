-- ============================================================================
-- Cash takings get their own asset account. Until now every receipt debited
-- BANK, so cash counted at the fee counter and money that had actually reached
-- the bank were one balance, and the office could not reconcile either against
-- a bank statement or a cash box. A cash payment now debits CASH; depositing
-- it is a CASH → BANK contra entry the accounts package makes, not us.
-- ============================================================================

INSERT INTO ledger_account (code, name, type, tally_ledger_name) VALUES
  ('CASH', 'Cash in Hand', 'asset', 'Cash')
ON CONFLICT (code) DO NOTHING;

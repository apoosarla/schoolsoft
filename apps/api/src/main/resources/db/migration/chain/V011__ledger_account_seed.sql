-- ============================================================================
-- Default chart-of-accounts rows so FeesRepository can post balanced journal
-- entries (bank receipt vs fee receivable) without every school configuring
-- Tally mappings before the first payment. Per §14 double-entry ledger.
-- ============================================================================

INSERT INTO ledger_account (code, name, type, tally_ledger_name) VALUES
  ('BANK',           'Bank',            'asset',   'Bank Account'),
  ('FEE_RECEIVABLE', 'Fee Receivable',  'asset',   'Fee Receivable'),
  ('FEE_INCOME',     'Fee Income',      'income',  'Fee Income'),
  ('DISCOUNT',       'Discount Given',  'expense', 'Discount Given'),
  ('REFUND',         'Refunds Payable', 'liability','Refunds Payable')
ON CONFLICT (code) DO NOTHING;

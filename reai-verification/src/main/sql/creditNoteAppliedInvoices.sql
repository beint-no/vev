-- vev.name: creditNoteAppliedInvoices :many
-- vev.notnull: credit_note_invoice_id,invoice_id,invoice_number
SELECT allocation.credit_note_invoice_id, applied.id AS invoice_id, applied.reference AS invoice_number
FROM invoice_ar_credit_note_allocations allocation
JOIN invoices_ar applied ON applied.id=allocation.invoice_id AND applied.tenant_id=allocation.tenant_id
WHERE allocation.tenant_id=:tenantId AND allocation.credit_note_invoice_id=ANY(:creditNoteInvoiceIds::int[])
ORDER BY allocation.credit_note_invoice_id,applied.issue_date,applied.id;

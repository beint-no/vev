-- vev.name: voucherSearchSources :many
-- vev.rows: 100000
-- vev.notnull: voucher_id,kind,source_id,priority
SELECT v.id AS voucher_id, source.kind, source.id AS source_id, source.number, source.priority, vat.year,
    CASE vat.period WHEN 'JAN_FEB' THEN 1 WHEN 'MAR_APR' THEN 2 WHEN 'MAY_JUN' THEN 3
        WHEN 'JUL_AUG' THEN 4 WHEN 'SEP_OCT' THEN 5 WHEN 'NOV_DEC' THEN 6 WHEN 'YEAR' THEN 1 END AS period_index,
    (receipt.occurred_at AT TIME ZONE 'Europe/Oslo')::date AS source_date
FROM vouchers v
LEFT JOIN vat_submissions vat ON vat.id = v.vat_submission_id AND vat.tenant_id = v.tenant_id
JOIN LATERAL (
    SELECT 'SUPPLIER_INVOICE' AS kind, si.id, si.number::text AS number, 0 AS priority
    FROM postings p JOIN supplier_invoice si ON si.id = p.supplier_invoice_id AND si.tenant_id = v.tenant_id
    WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id AND v.voucher_type IN ('SUPPLIER_INVOICE', 'PAYMENT', 'MANUAL')
    UNION
    SELECT 'CUSTOMER_INVOICE', i.id, i.reference::text, 0
    FROM invoices_ar i WHERE i.voucher_id = v.id AND i.tenant_id = v.tenant_id
    UNION
    SELECT 'CUSTOMER_INVOICE', i.id, i.reference::text, 0
    FROM invoice_ar_payments p JOIN invoices_ar i ON i.id = p.invoice_id AND i.tenant_id = v.tenant_id
    WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id AND v.voucher_type = 'PAYMENT'
    UNION
    SELECT 'CUSTOMER_INVOICE', i.id, i.reference::text, 0
    FROM invoice_ar_rounding_adjustments a JOIN invoices_ar i ON i.id = a.invoice_id AND i.tenant_id = v.tenant_id
    WHERE a.voucher_id = v.id AND a.tenant_id = v.tenant_id AND v.voucher_type = 'CUSTOMER_INVOICE_ADJUSTMENT'
    UNION
    SELECT 'CUSTOMER_INVOICE', i.id, i.reference::text, 0
    FROM invoice_ar_credit_note_allocations a JOIN invoices_ar i ON i.id = a.credit_note_invoice_id AND i.tenant_id = v.tenant_id
    WHERE a.currency_adjustment_voucher_id = v.id AND a.tenant_id = v.tenant_id AND v.voucher_type = 'CREDIT_NOTE_ASSIGNMENT'
    UNION
    SELECT 'SALARY', s.id, s.number::text || '-' || s.period_year, 0
    FROM salary_payments s WHERE s.voucher_id = v.id AND s.tenant_id = v.tenant_id AND v.voucher_type = 'SALARY'
    UNION
    SELECT 'SALARY', s.id, s.number::text || '-' || s.period_year, 0
    FROM salary_holiday_accrual_corrections c
    JOIN employee_salary_payments e ON e.id = c.employee_salary_payment_id AND e.tenant_id = v.tenant_id
    JOIN salary_payments s ON s.id = e.salary_payment_id AND s.tenant_id = v.tenant_id
    WHERE c.voucher_id = v.id AND c.tenant_id = v.tenant_id AND v.voucher_type = 'SALARY'
    UNION
    SELECT 'EXPENSE', e.id, e.number::text, 0
    FROM expenses e WHERE e.voucher_id = v.id AND e.tenant_id = v.tenant_id AND v.voucher_type = 'EXPENSE'
    UNION
    SELECT 'DRIVING_LOG', d.id, NULL, 0
    FROM driving_log_trips d WHERE d.voucher_id = v.id AND d.tenant_id = v.tenant_id AND v.voucher_type = 'EXPENSE'
    UNION
    SELECT 'CUSTOMS_DECLARATION', c.id, c.number::text, 0
    FROM customs_declarations c WHERE c.voucher_id = v.id AND c.tenant_id = v.tenant_id AND v.voucher_type = 'CUSTOMS_DECLARATION'
    UNION
    SELECT 'ASSET', p.asset_id, NULL, 0
    FROM postings p WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id AND p.asset_id IS NOT NULL
        AND v.voucher_type IN ('ASSET', 'ASSET_DEPRECIATION')
    UNION
    SELECT 'LOAN', e.loan_id, NULL, 0
    FROM loan_events e WHERE e.voucher_id = v.id AND e.tenant_id = v.tenant_id AND v.voucher_type = 'LOAN'
    UNION
    SELECT 'SHARE_INVESTMENT', e.share_investment_id, NULL, 0
    FROM share_investment_events e WHERE e.voucher_id = v.id AND e.tenant_id = v.tenant_id AND v.voucher_type = 'SHARE_INVESTMENT'
    UNION
    SELECT 'SUPPLIER_INVOICE', si.id, si.number::text, 0
    FROM payments p JOIN supplier_invoice si ON si.id = p.invoice_id AND si.tenant_id = v.tenant_id
    WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id AND v.voucher_type = 'PAYMENT'
    UNION
    SELECT 'KRAVIA_SETTLEMENT', k.id, k.external_settlement_id::text, -1
    FROM kravia_settlements k WHERE k.voucher_id = v.id AND k.tenant_id = v.tenant_id AND v.voucher_type = 'PAYMENT'
    UNION
    SELECT 'VAT_RETURN', vat.id, NULL, 0
    WHERE vat.id IS NOT NULL AND v.voucher_type = 'VAT_RETURN'
    UNION
    SELECT 'POS_RECEIPT', event.id, event.receipt_number, -1
    FROM kassasystem_journal_event_postings link
    JOIN kassasystem_journal_events event ON event.id = link.journal_event_id AND event.tenant_id = v.tenant_id
    WHERE link.voucher_id = v.id AND link.tenant_id = v.tenant_id AND v.voucher_type = 'INCOME'
    UNION
    SELECT 'INCOME', v.id, NULL, 0
    WHERE v.voucher_type = 'INCOME' AND EXISTS (
        SELECT 1 FROM postings p WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id
            AND p.customer_id IS NOT NULL AND p.account_number = '1500' AND p.company_bank_id IS NULL
            AND NOT p.is_vat_posting
    )
    UNION
    SELECT 'PAYMENT', p.id, NULL, 1
    FROM payments p WHERE p.voucher_id = v.id AND p.tenant_id = v.tenant_id AND v.voucher_type = 'PAYMENT'
) source ON true
LEFT JOIN kassasystem_journal_events receipt ON source.kind = 'POS_RECEIPT' AND receipt.id = source.id AND receipt.tenant_id = v.tenant_id
WHERE v.tenant_id = :tenantId AND v.id = ANY(:voucherIds::bigint[]);

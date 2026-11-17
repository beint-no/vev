-- vev.name: voucherOverview :many
-- vev.rows: 100000
-- vev.notnull: voucher_id,voucher_number,voucher_date,voucher_created_at,voucher_description,voucher_type
SELECT v.id AS voucher_id,v.number AS voucher_number,v.date AS voucher_date,v.created_at AS voucher_created_at,
       coalesce(v.description,'') AS voucher_description,v.voucher_type,v.reversed_at AS voucher_reversed_at,
       p.id AS posting_id,p.posting_date,p.description AS posting_description,p.account_number,p.vat_code,p.amount,
       p.company_bank_id,cb.name AS company_bank_name,
       coalesce(sc.name,sp.name,cc.name,cp.name,ep.name,a.name) AS subsidiary_ledger_name,
       coalesce(sc.name,sp.name) AS supplier_name,si.number AS supplier_invoice_number,
       p.reverses_posting_id AS posting_reverses_posting_id,p.supplier_id AS posting_supplier_id,
       p.supplier_invoice_id AS posting_supplier_invoice_id
FROM vouchers v
LEFT JOIN postings p ON p.voucher_id=v.id AND p.tenant_id=v.tenant_id
LEFT JOIN company_bank cb ON cb.id=p.company_bank_id AND cb.tenant_id=v.tenant_id
LEFT JOIN suppliers s ON s.id=p.supplier_id AND s.tenant_id=v.tenant_id
LEFT JOIN companies sc ON sc.id=s.company_id AND sc.tenant_id=v.tenant_id
LEFT JOIN persons sp ON sp.id=s.person_id AND sp.tenant_id=v.tenant_id
LEFT JOIN customers c ON c.id=p.customer_id AND c.tenant_id=v.tenant_id
LEFT JOIN companies cc ON cc.id=c.company_id AND cc.tenant_id=v.tenant_id
LEFT JOIN persons cp ON cp.id=c.person_id AND cp.tenant_id=v.tenant_id
LEFT JOIN employees e ON e.id=p.employee_id AND e.tenant_id=v.tenant_id
LEFT JOIN persons ep ON ep.id=e.person_id AND ep.tenant_id=v.tenant_id
LEFT JOIN assets a ON a.id=p.asset_id AND a.tenant_id=v.tenant_id
LEFT JOIN supplier_invoice si ON si.id=p.supplier_invoice_id AND si.tenant_id=v.tenant_id
WHERE v.tenant_id=:tenantId AND v.id=ANY(:voucherIds::bigint[])
ORDER BY v.number DESC,p.row_number,p.id;

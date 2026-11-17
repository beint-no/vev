-- name: ledger :many
-- rows: 1000
SELECT p.id,p.voucher_id,p.posting_date,p.account_number,p.amount,p.currency,p.description,
       d.name AS department_name FROM postings p
JOIN departments d ON d.id=p.department_id AND d.tenant_id=p.tenant_id
WHERE p.tenant_id=:tenantId ORDER BY p.id;

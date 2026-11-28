-- vev.name: reverseChargeQuarters :many
-- vev.notnull: last_posting_date
SELECT max(p.posting_date) AS last_posting_date
FROM postings p JOIN vouchers v ON v.id=p.voucher_id AND v.tenant_id=p.tenant_id
WHERE p.tenant_id=:tenantId AND p.vat_code='87' AND v.vat_submission_id IS NULL
  AND p.posting_date>=:fromDate AND p.posting_date<:beforeDate
GROUP BY extract(year FROM p.posting_date),extract(quarter FROM p.posting_date)
HAVING sum(coalesce(p.vat_basis_amount,p.amount))>2000;

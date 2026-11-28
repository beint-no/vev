-- vev.name: vatBasis :many
-- vev.notnull: vat_code,basis_amount
SELECT p.vat_code, sum(coalesce(p.vat_basis_amount,p.amount)) AS basis_amount
FROM postings p LEFT JOIN vouchers v ON v.id=p.voucher_id AND v.tenant_id=p.tenant_id
WHERE p.tenant_id=:tenantId AND p.posting_date BETWEEN :startDate AND :endDate
  AND p.vat_code IS NOT NULL AND p.vat_code<>'' AND v.vat_submission_id IS NULL
GROUP BY p.vat_code ORDER BY p.vat_code;

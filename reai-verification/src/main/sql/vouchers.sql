-- name: vouchers :many
-- rows: 1000
-- notnull: id,date,total
WITH amounts AS (
    SELECT voucher_id,tenant_id,sum(amount) AS total FROM postings
    WHERE tenant_id=:tenantId GROUP BY voucher_id,tenant_id
)
SELECT v.id,v.date,v.description,a.total FROM vouchers v
JOIN amounts a ON a.voucher_id=v.id AND a.tenant_id=v.tenant_id
WHERE v.tenant_id=:tenantId AND v.id = ANY(:ids::bigint[]) ORDER BY v.id;

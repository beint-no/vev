-- name: runningBalance :many
-- rows: 1000
-- notnull: id,balance
SELECT id,account_number,sum(amount) OVER(PARTITION BY account_number ORDER BY id ROWS UNBOUNDED PRECEDING) AS balance
FROM postings WHERE tenant_id=:tenantId ORDER BY id;

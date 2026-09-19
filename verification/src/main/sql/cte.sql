-- name: totals :many
WITH totals AS (
    SELECT customer_id, sum(amount) AS total FROM invoice
    WHERE tenant_id = :tenantId GROUP BY customer_id
)
SELECT c.id, c.name, t.total FROM customer c
LEFT JOIN totals t ON t.customer_id = c.id
WHERE c.tenant_id = :tenantId ORDER BY c.id;

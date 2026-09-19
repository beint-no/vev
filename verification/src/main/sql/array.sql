-- name: customerIds :many
SELECT id FROM customer WHERE tenant_id = :tenantId AND id = ANY(:ids::integer[]) ORDER BY id;

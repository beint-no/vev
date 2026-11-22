-- vev.name: customers :many
-- vev.nullable: name
SELECT id, name FROM customer
WHERE tenant_id = :tenantId AND (CAST(:name AS text) IS NULL OR name ILIKE :name)
ORDER BY id;

-- name: exactlyOne :one
SELECT id FROM customer WHERE tenant_id = :tenantId;

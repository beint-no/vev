-- name: customer :optional
-- type: id CustomerId
SELECT id, tenant_id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;

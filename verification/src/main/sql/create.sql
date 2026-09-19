-- name: createCustomer :one
-- type: id CustomerId
INSERT INTO customer (tenant_id, name) VALUES (:tenantId, :name) RETURNING id, name;

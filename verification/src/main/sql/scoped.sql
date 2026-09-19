-- name: scopedCustomer :optional
-- tenant: tenantId
-- type: id CustomerId
SELECT id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;

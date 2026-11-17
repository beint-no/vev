-- name: scopedCustomer :optional
-- type: id CustomerId
SELECT id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;

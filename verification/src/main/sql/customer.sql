-- vev.name: customer :optional
-- vev.type: id CustomerId
SELECT id, tenant_id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;

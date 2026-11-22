-- vev.name: scopedCustomer :optional
-- vev.type: id CustomerId
SELECT id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;

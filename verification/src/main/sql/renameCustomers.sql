-- vev.name: renameCustomers :exec
UPDATE customer SET name=:name WHERE tenant_id=:tenantId AND id=ANY(:ids::int[]);

-- vev.name: department :optional
SELECT id,name,archived FROM departments WHERE tenant_id=:tenantId AND id=:id;

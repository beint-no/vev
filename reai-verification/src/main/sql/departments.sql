-- vev.name: departments :many
SELECT id,name,archived FROM departments WHERE tenant_id=:tenantId ORDER BY id;

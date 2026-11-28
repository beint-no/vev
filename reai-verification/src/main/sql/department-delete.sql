-- vev.name: deleteDepartment :exec
DELETE FROM departments WHERE tenant_id=:tenantId AND id=:id;

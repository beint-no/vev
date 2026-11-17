-- name: renameDepartment :exec
UPDATE departments SET name=:name WHERE tenant_id=:tenantId AND id=:id;

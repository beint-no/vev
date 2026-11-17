-- name: bulkDepartments :exec
INSERT INTO departments(id,tenant_id,name)
SELECT n,:tenantId,'Synthetic Bulk '||n FROM unnest(:ids::int[]) AS n;

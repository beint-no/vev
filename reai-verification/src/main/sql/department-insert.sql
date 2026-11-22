-- vev.name: createDepartment :one
-- vev.notnull: id,name
INSERT INTO departments(id,tenant_id,name) VALUES (:id,:tenantId,:name) RETURNING id,name;

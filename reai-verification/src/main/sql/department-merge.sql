-- vev.name: mergeDepartment :one
-- vev.notnull: id,name
MERGE INTO departments d
USING (VALUES (:id::int,:tenantId::int,:name::varchar)) AS source(id,tenant_id,name)
ON d.id=source.id AND d.tenant_id=source.tenant_id
WHEN MATCHED THEN UPDATE SET name=source.name
WHEN NOT MATCHED THEN INSERT(id,tenant_id,name) VALUES(source.id,source.tenant_id,source.name)
RETURNING d.id,d.name;

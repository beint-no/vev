-- vev.name: createSyntheticCustomersOne :one
INSERT INTO customer(tenant_id,name) SELECT :tenantId,'Synthetic '||n FROM generate_series(1,3) n RETURNING id;

-- vev.name: writeDecodeFailure :one
-- vev.notnull: bad
INSERT INTO write_settings(tenant_id) VALUES(:tenantId) RETURNING NULL::int AS bad;

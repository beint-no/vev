-- name: documentsByEmail :many
-- tenant: tenantId
SELECT id FROM document_record
WHERE tenant_id = :tenantId AND email = :email::citext
ORDER BY id;

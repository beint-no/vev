-- name: documentStates :many
-- tenant: tenantId
-- enum: status no.beint.vev.fixture.DocumentStatus
-- notnull: status, total
WITH counted AS (
    SELECT status, count(*) AS total
    FROM document_record
    WHERE tenant_id = :tenantId
    GROUP BY status
)
SELECT status, total FROM counted ORDER BY status;

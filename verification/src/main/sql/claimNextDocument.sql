-- vev.name: claimNextDocument :optional
-- vev.scope: global
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
SELECT id, tenant_id, status
FROM document_record
WHERE status = 'DRAFT'
ORDER BY id
FOR UPDATE SKIP LOCKED
LIMIT 1;

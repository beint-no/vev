-- vev.name: document :optional
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
-- vev.enum: review no.beint.vev.fixture.ReviewState
SELECT id, status, review, email, body::text AS body, address::text AS address
FROM document_record
WHERE tenant_id = :tenantId AND id = :id;

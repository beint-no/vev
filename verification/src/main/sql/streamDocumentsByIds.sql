-- vev.name: streamDocumentsByIds :stream
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
-- vev.notnull: id,status
SELECT d.id, d.status
FROM document_record d
LEFT JOIN customer c ON c.id = d.id AND c.tenant_id = :tenantId
WHERE d.tenant_id = :tenantId AND d.id = ANY(:ids::int[])
ORDER BY d.id;

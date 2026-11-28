-- vev.name: latestDocumentByEmails :many
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
SELECT DISTINCT ON (email)
       email, id, status
FROM document_record
WHERE tenant_id = :tenantId
  AND email::text = ANY(:emails::text[])
ORDER BY email, id DESC;

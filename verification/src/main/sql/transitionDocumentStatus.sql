-- vev.name: transitionDocumentStatus :exec
-- vev.scope: global
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
UPDATE document_record
SET status = :status,
    review = CASE WHEN :status::varchar = 'PAID' THEN 'APPROVED' ELSE review END
WHERE id = :id;

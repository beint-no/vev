-- name: createDocument :one
-- tenant: tenantId
-- enum: status no.beint.vev.fixture.DocumentStatus
-- enum: review no.beint.vev.fixture.ReviewState
-- nullable: review, body, address
-- notnull: id
INSERT INTO document_record (tenant_id, status, review, email, body, address)
VALUES (:tenantId, :status, :review, :email, :body::xml, :address::inet)
RETURNING id;

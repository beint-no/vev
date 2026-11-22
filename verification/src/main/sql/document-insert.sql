-- vev.name: createDocument :one
-- vev.enum: status no.beint.vev.fixture.DocumentStatus
-- vev.enum: review no.beint.vev.fixture.ReviewState
-- vev.nullable: review, body, address
-- vev.notnull: id
INSERT INTO document_record (tenant_id, status, review, email, body, address)
VALUES (:tenantId, :status, :review, :email, :body::xml, :address::inet)
RETURNING id;

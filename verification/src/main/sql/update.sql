-- name: updateInvoice :optional
-- nullable: note
-- type: id InvoiceId
UPDATE invoice SET amount = :amount, note = :note, version = version + 1
WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
RETURNING id, version, amount, note;

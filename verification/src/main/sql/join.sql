-- name: invoiceSummary :many
-- type: customer_id CustomerId
-- type: invoice_id InvoiceId
SELECT c.id AS customer_id, c.name, i.id AS invoice_id, i.amount, i.note
FROM customer c LEFT JOIN invoice i ON i.customer_id = c.id AND i.tenant_id = c.tenant_id
WHERE c.tenant_id = :tenantId ORDER BY c.id, i.id;

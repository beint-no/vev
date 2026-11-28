-- vev.name: voucherDocuments :many
-- vev.notnull: voucher_id,voucher_type
SELECT v.id AS voucher_id,v.voucher_type,si.id AS supplier_invoice_id,invoice.id AS invoice_id,
       salary.id AS salary_payment_id,expense.id AS expense_id,customs.id AS customs_declaration_id,
       (SELECT max(link.id) FROM supplier_invoice_attachments link
        JOIN attachments attachment ON attachment.id=link.attachment_id AND attachment.tenant_id=v.tenant_id
        WHERE link.supplier_invoice_id=si.id AND link.tenant_id=v.tenant_id
          AND attachment.mimetype IN ('application/pdf','application/xml','image/jpeg','image/png','image/webp')) AS supplier_invoice_attachment_id,
       (SELECT max(link.id) FROM expense_attachments link
        JOIN attachments attachment ON attachment.id=link.attachment_id AND attachment.tenant_id=v.tenant_id
        WHERE link.expense_id=expense.id AND link.tenant_id=v.tenant_id
          AND attachment.mimetype IN ('application/pdf','application/xml','image/jpeg','image/png','image/webp')) AS expense_attachment_id,
       (SELECT max(link.id) FROM customs_declaration_attachments link
        JOIN attachments attachment ON attachment.id=link.attachment_id AND attachment.tenant_id=v.tenant_id
        WHERE link.customs_declaration_id=customs.id AND link.tenant_id=v.tenant_id
          AND attachment.mimetype IN ('application/pdf','application/xml','image/jpeg','image/png','image/webp')) AS customs_declaration_attachment_id,
       (SELECT max(link.id) FROM voucher_attachments link
        JOIN attachments attachment ON attachment.id=link.attachment_id AND attachment.tenant_id=v.tenant_id
        WHERE link.voucher_id=v.id AND link.tenant_id=v.tenant_id
          AND attachment.mimetype IN ('application/pdf','application/xml','image/jpeg','image/png','image/webp')) AS voucher_attachment_id
FROM vouchers v
LEFT JOIN supplier_invoice si ON v.voucher_type='SUPPLIER_INVOICE' AND si.tenant_id=v.tenant_id
  AND si.id=(SELECT max(p.supplier_invoice_id) FROM postings p WHERE p.voucher_id=v.id AND p.tenant_id=v.tenant_id)
LEFT JOIN invoices_ar invoice ON invoice.voucher_id=v.id AND invoice.tenant_id=v.tenant_id AND v.voucher_type='CUSTOMER_INVOICE'
LEFT JOIN salary_payments salary ON salary.voucher_id=v.id AND salary.tenant_id=v.tenant_id AND v.voucher_type='SALARY'
LEFT JOIN expenses expense ON expense.voucher_id=v.id AND expense.tenant_id=v.tenant_id AND v.voucher_type='EXPENSE'
LEFT JOIN customs_declarations customs ON customs.voucher_id=v.id AND customs.tenant_id=v.tenant_id AND v.voucher_type='CUSTOMS_DECLARATION'
WHERE v.tenant_id=:tenantId AND v.id=ANY(:voucherIds::bigint[]);

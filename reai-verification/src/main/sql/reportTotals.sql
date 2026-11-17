-- name: reportTotals :many
-- rows: 100000
-- notnull: name,amount

-- Manual vouchers infer parties only within a balanced same-date debit/credit row.
-- Invoice vouchers may use their sole counterparty across rows. Retain booked reversals.
WITH selected AS (
    SELECT p.id, p.tenant_id, p.voucher_id, p.row_number, p.posting_date,
           p.account_number, p.description, p.amount, p.supplier_id, p.customer_id,
           p.supplier_invoice_id, p.is_vat_posting FROM postings p
    WHERE p.tenant_id = :tenantId
      AND p.posting_date BETWEEN :startDate AND :endDate
      AND p.account_number = ANY(:accounts::text[])
), voucher_postings AS (
    SELECT p.id, p.tenant_id, p.voucher_id, p.row_number, p.posting_date,
           p.account_number, p.description, p.amount, p.supplier_id, p.customer_id,
           p.supplier_invoice_id, p.is_vat_posting FROM postings p
    WHERE p.tenant_id = :tenantId
      AND p.voucher_id IN (SELECT voucher_id FROM selected)
), row_parties AS (
    SELECT p.voucher_id, p.row_number, p.posting_date,
           CASE WHEN COUNT(DISTINCT p.supplier_id) = 1 THEN MIN(p.supplier_id) END AS supplier_id,
           CASE WHEN COUNT(DISTINCT p.customer_id) = 1 THEN MIN(p.customer_id) END AS customer_id
    FROM voucher_postings p
    GROUP BY p.voucher_id, p.row_number, p.posting_date
    HAVING SUM(p.amount) = 0
       AND COUNT(*) FILTER (WHERE NOT COALESCE(p.is_vat_posting, false) AND p.amount > 0) <= 1
       AND COUNT(*) FILTER (WHERE NOT COALESCE(p.is_vat_posting, false) AND p.amount < 0) <= 1
), invoice_parties AS (
    SELECT p.voucher_id,
           CASE WHEN v.voucher_type = 'SUPPLIER_INVOICE' AND COUNT(DISTINCT p.supplier_id) = 1
                THEN MIN(p.supplier_id) END AS supplier_id,
           CASE WHEN v.voucher_type = 'CUSTOMER_INVOICE' AND COUNT(DISTINCT p.customer_id) = 1
                THEN MIN(p.customer_id) END AS customer_id
    FROM voucher_postings p
    JOIN vouchers v ON v.id = p.voucher_id AND v.tenant_id = p.tenant_id
    WHERE v.voucher_type IN ('SUPPLIER_INVOICE', 'CUSTOMER_INVOICE')
    GROUP BY p.voucher_id, v.voucher_type
), resolved AS (
    SELECT p.id, p.tenant_id, p.voucher_id, p.posting_date, p.account_number, p.description, p.amount,
           CASE WHEN :suppliers
        THEN COALESCE(p.supplier_id, si.supplier_id, rp.supplier_id, ip.supplier_id)
        ELSE COALESCE(p.customer_id, rp.customer_id, ip.customer_id) END AS resolved_party_id
    FROM selected p
    LEFT JOIN supplier_invoice si ON si.id = p.supplier_invoice_id AND si.tenant_id = p.tenant_id
    LEFT JOIN row_parties rp ON rp.voucher_id = p.voucher_id AND rp.row_number = p.row_number AND rp.posting_date = p.posting_date
    LEFT JOIN invoice_parties ip ON ip.voucher_id = p.voucher_id
), attributed AS (
    SELECT p.id, p.voucher_id, p.posting_date, p.account_number, p.description,
           CASE WHEN :suppliers THEN p.amount ELSE -p.amount END AS amount,
           CASE WHEN :suppliers THEN s.id ELSE cu.id END AS party_id,
           COALESCE(cd.name, pe.name, '') AS name
    FROM resolved p
    LEFT JOIN suppliers s ON :suppliers AND s.id = p.resolved_party_id AND s.tenant_id = p.tenant_id
    LEFT JOIN customers cu ON NOT :suppliers AND cu.id = p.resolved_party_id AND cu.tenant_id = p.tenant_id
    LEFT JOIN companies co ON co.id = COALESCE(s.company_id, cu.company_id) AND co.tenant_id = p.tenant_id
    LEFT JOIN company_details cd ON cd.id = co.id
    LEFT JOIN persons pe ON pe.id = COALESCE(s.person_id, cu.person_id) AND pe.tenant_id = p.tenant_id
    WHERE NOT :suppliers OR s.id IS NOT NULL
)
SELECT party_id, name, SUM(amount) AS amount
    FROM attributed
    GROUP BY party_id, name
    HAVING SUM(amount) <> 0
    ORDER BY SUM(amount) DESC, name, party_id;

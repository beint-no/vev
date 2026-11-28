-- vev.name: createPosting :exec
INSERT INTO postings(tenant_id,voucher_id,created_at,updated_at,posting_date,row_number,account_number,amount,currency,vat_code,currency_amount,department_id)
VALUES (:tenantId,:voucherId,:created,:created,:day,:rowNumber,:account,:amount,'NOK','0',:amount,:departmentId);

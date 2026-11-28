-- Entirely synthetic fixtures. No production rows or credentials.
INSERT INTO tenants(id,company_id,created_at,updated_at,slug)
SELECT n,n,'2026-01-01Z','2026-01-01Z','synthetic-tenant-'||n FROM generate_series(1,3) n;
INSERT INTO companies(id,tenant_id,created_at,updated_at,name,country_code,company_type_code)
SELECT n,n,'2026-01-01Z','2026-01-01Z','Synthetic Company '||n,'SE','AB' FROM generate_series(1,3) n;
INSERT INTO departments(id,tenant_id,name)
SELECT n,t,'Synthetic Department '||n FROM generate_series(1,3) t CROSS JOIN generate_series(1,20) n;
INSERT INTO vouchers(id,tenant_id,created_at,updated_at,date,number,description)
SELECT n,t,'2026-01-01Z','2026-01-01Z','2026-01-01'::date+(n%28),n,'Synthetic Voucher '||n
FROM generate_series(1,3) t CROSS JOIN generate_series(1,300) n;
INSERT INTO postings(tenant_id,voucher_id,created_at,updated_at,posting_date,row_number,account_number,amount,currency,vat_code,currency_amount,department_id)
SELECT t,n,'2026-01-01Z','2026-01-01Z','2026-01-01'::date+(n%28),side,
       CASE side WHEN 1 THEN '3000' ELSE '1920' END,
       CASE side WHEN 1 THEN n::numeric ELSE -n::numeric END,'NOK','0',
       CASE side WHEN 1 THEN n::numeric ELSE -n::numeric END,(n%20)+1
FROM generate_series(1,3) t CROSS JOIN generate_series(1,300) n CROSS JOIN generate_series(1,2) side;

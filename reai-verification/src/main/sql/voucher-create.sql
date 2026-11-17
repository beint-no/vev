-- name: createVoucher :exec
INSERT INTO vouchers(id,tenant_id,created_at,updated_at,date,number,description)
VALUES (:id,:tenantId,:created,:created,:day,:number,:description);

-- name: post :exec
-- scope: global
INSERT INTO posting (voucher_id, amount) VALUES (:voucherId, :amount);

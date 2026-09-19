-- name: post :exec
INSERT INTO posting (voucher_id, amount) VALUES (:voucherId, :amount);

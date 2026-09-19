-- name: scalars :optional
SELECT id, active, small, large, precise, real_value, double_value, label, uid,
       day, clock, local_stamp, stamp, payload, document, numbers, tags
FROM scalar_values WHERE id = :id;

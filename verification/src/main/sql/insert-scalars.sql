-- name: insertScalars :exec
-- nullable: numbers, tags
INSERT INTO scalar_values (id, active, small, large, precise, real_value, double_value, label,
    uid, day, clock, local_stamp, stamp, payload, document, numbers, tags)
VALUES (:id, :active, :small, :large, :precise, :realValue, :doubleValue, :label,
    :uid, :day, :clock, :localStamp, :stamp, :payload, :document, :numbers, :tags);

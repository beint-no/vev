-- name: quoted :one
SELECT $$:not_a_parameter; $dollars$$ AS text_value, ':also_not' AS other_value;

-- vev.name: requiredPrimitives :one
-- vev.scope: global
-- vev.notnull: b,s,i,l,f,d
SELECT :b::bool AS b, :s::int2 AS s, :i::int4 AS i, :l::int8 AS l,
       :f::float4 AS f, :d::float8 AS d

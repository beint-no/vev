CREATE TABLE customer (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id integer NOT NULL,
    name text NOT NULL,
    UNIQUE (id, tenant_id)
);
CREATE TABLE invoice (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id integer NOT NULL,
    customer_id integer NOT NULL,
    amount numeric(18,2) NOT NULL,
    note text,
    version integer NOT NULL DEFAULT 0,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customer (id, tenant_id)
);
CREATE TABLE posting (voucher_id integer NOT NULL, amount numeric NOT NULL);
CREATE FUNCTION balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT coalesce(sum(amount), 0) FROM posting WHERE voucher_id = NEW.voucher_id) <> 0 THEN
        RAISE EXCEPTION 'unbalanced voucher';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balance AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION balanced();
CREATE TABLE scalar_values (
    id integer PRIMARY KEY,
    active boolean NOT NULL,
    small smallint NOT NULL,
    large bigint NOT NULL,
    precise numeric NOT NULL,
    real_value real NOT NULL,
    double_value double precision NOT NULL,
    label text NOT NULL,
    uid uuid NOT NULL,
    day date NOT NULL,
    clock time NOT NULL,
    local_stamp timestamp NOT NULL,
    stamp timestamptz NOT NULL,
    payload bytea NOT NULL,
    document jsonb NOT NULL,
    numbers integer[],
    tags text[]
);

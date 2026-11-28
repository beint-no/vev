CREATE TABLE write_settings (
    tenant_id int PRIMARY KEY,
    amount numeric(10,2) CHECK (amount > 0 AND amount <= 10000000),
    label text NOT NULL DEFAULT 'initial',
    normalized text GENERATED ALWAYS AS (upper(label)) STORED,
    revision int NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION normalize_write_settings() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.label = btrim(NEW.label);
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER normalize_write_settings BEFORE INSERT OR UPDATE ON write_settings
FOR EACH ROW EXECUTE FUNCTION normalize_write_settings();

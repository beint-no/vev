CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE EXTENSION IF NOT EXISTS citext;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

SET LOCAL check_function_bodies = false;

CREATE FUNCTION assign_a_melding_report_number() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO a_melding_report_counters (tenant_id, last_number)
    VALUES (NEW.tenant_id, 1)
    ON CONFLICT (tenant_id) DO UPDATE
    SET last_number = a_melding_report_counters.last_number + 1
    RETURNING last_number INTO NEW.report_number;
    RETURN NEW;
END;
$$;

CREATE FUNCTION check_checkout_discount_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.discount_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM site_commerce_discounts d
        WHERE d.id = NEW.discount_id AND d.tenant_id = NEW.tenant_id
          AND (d.all_sales_channels OR (d.market_id = NEW.market_id AND d.site_id = NEW.site_id))
    ) THEN
        RAISE EXCEPTION 'Discount is not available on this sales channel' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION company_address_title_case(value text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
    SELECT string_agg(
        CASE WHEN word IN ('as', 'asa') THEN upper(word) ELSE upper(left(word, 1)) || substr(word, 2) END,
        ' ' ORDER BY position)
    FROM regexp_split_to_table(lower(btrim(value)), '\s+') WITH ORDINALITY AS words(word, position)
$$;

CREATE FUNCTION delete_empty_posting_group_after_link_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE
    FROM posting_groups pg
    WHERE pg.id = old.posting_group_id
      AND pg.tenant_id = old.tenant_id
      AND NOT EXISTS (
        SELECT 1
        FROM posting_group_postings pgp
        WHERE pgp.posting_group_id = old.posting_group_id
          AND pgp.tenant_id = old.tenant_id
    );

    RETURN old;
END;
$$;

CREATE FUNCTION delete_empty_reconciliation_group_after_link_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE
    FROM reconciliation_groups rg
    WHERE rg.id = old.reconciliation_group_id
        AND rg.tenant_id = old.tenant_id
        AND NOT EXISTS (
            SELECT 1
            FROM transaction_postings tp
            WHERE tp.reconciliation_group_id = old.reconciliation_group_id
                AND tp.tenant_id = old.tenant_id
        );

    RETURN old;
END;
$$;

CREATE FUNCTION detach_adyen_liable_transactions_before_tenant_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE adyen_transactions transaction
    SET tenant_id = NULL,
        adyen_balance_account_id = NULL,
        accounting_voucher_id = NULL,
        projection_status = 'BLOCKED',
        blocked_reason = 'BALANCE_ACCOUNT_NOT_MAPPED',
        accounting_status = 'NOT_APPLICABLE',
        accounting_failure_reason = 'SELLER_TENANT_DELETED',
        accounted_at = NULL,
        updated_at = now()
    WHERE transaction.tenant_id = OLD.id
      AND EXISTS (
          SELECT 1
          FROM adyen_liable_accounts liable
          WHERE liable.balance_account_id = transaction.balance_account_id
            AND liable.tenant_id <> OLD.id
      );
    RETURN OLD;
END;
$$;

CREATE FUNCTION ensure_invoice_ar_credit_note_allocation_is_valid(allocation_id_to_check integer) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    invalid_allocation_count INTEGER;
BEGIN
    SELECT COUNT(*)
    INTO invalid_allocation_count
    FROM public.invoice_ar_credit_note_allocations allocation
    JOIN public.invoices_ar invoice
      ON invoice.id = allocation.invoice_id
     AND invoice.tenant_id = allocation.tenant_id
    JOIN public.invoices_ar credit_note
      ON credit_note.id = allocation.credit_note_invoice_id
     AND credit_note.tenant_id = allocation.tenant_id
    WHERE allocation.id = allocation_id_to_check
      AND (
          NOT EXISTS (
              SELECT 1 FROM public.invoice_ar_source_orders source WHERE source.invoice_id = invoice.id
          )
          OR EXISTS (
              SELECT 1
              FROM public.invoice_ar_credit_note_allocations invoice_credit_note_allocation
              WHERE invoice_credit_note_allocation.tenant_id = invoice.tenant_id
                AND invoice_credit_note_allocation.credit_note_invoice_id = invoice.id
          )
          OR (
              allocation.allocation_type = 'INVOICE_FULL_REVERSAL'
              AND EXISTS (
                  SELECT 1 FROM public.invoice_ar_source_orders source WHERE source.invoice_id = credit_note.id
              )
          )
          OR (
              allocation.allocation_type IN (
                  'MANUAL_MATCHES_ORIGINAL_INVOICE_AMOUNT',
                  'MANUAL_PARTIAL_COVER'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM public.invoice_ar_source_orders source WHERE source.invoice_id = credit_note.id
              )
          )
      );

    IF invalid_allocation_count > 0 THEN
        RAISE EXCEPTION 'Invalid invoice AR credit note allocation %', allocation_id_to_check;
    END IF;
END;
$$;

CREATE FUNCTION ensure_invoice_ar_has_source_or_allocation(invoice_id_to_check integer, tenant_id_to_check integer) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.invoices_ar invoice
        WHERE invoice.id = invoice_id_to_check
          AND invoice.tenant_id = tenant_id_to_check
          AND NOT EXISTS (
              SELECT 1 FROM public.invoice_ar_source_orders source WHERE source.invoice_id = invoice.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM public.invoice_ar_credit_note_allocations allocation
              WHERE allocation.tenant_id = invoice.tenant_id
                AND allocation.credit_note_invoice_id = invoice.id
                AND allocation.allocation_type = 'INVOICE_FULL_REVERSAL'
          )
    ) THEN
        RAISE EXCEPTION 'Invoice AR % must have a source order or full reversal credit note allocation',
            invoice_id_to_check;
    END IF;
END;
$$;

CREATE FUNCTION ensure_postings_balance_per_posting_date() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    residual numeric;
BEGIN
    IF (tg_op = 'INSERT' OR tg_op = 'UPDATE') AND new.voucher_id IS NOT NULL THEN
        SELECT sum(amount) INTO residual
        FROM postings
        WHERE voucher_id = new.voucher_id
          AND posting_date = new.posting_date;
        IF residual IS NOT NULL AND residual <> 0 THEN
            RAISE EXCEPTION 'Voucher % does not balance on posting date %: residual %',
                new.voucher_id, new.posting_date, residual;
        END IF;
    END IF;

    IF (tg_op = 'UPDATE' OR tg_op = 'DELETE') AND old.voucher_id IS NOT NULL
        AND (tg_op = 'DELETE'
            OR new.voucher_id IS DISTINCT FROM old.voucher_id
            OR new.posting_date IS DISTINCT FROM old.posting_date) THEN
        SELECT sum(amount) INTO residual
        FROM postings
        WHERE voucher_id = old.voucher_id
          AND posting_date = old.posting_date;
        IF residual IS NOT NULL AND residual <> 0 THEN
            RAISE EXCEPTION 'Voucher % does not balance on posting date %: residual %',
                old.voucher_id, old.posting_date, residual;
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE FUNCTION ensure_postings_do_not_leave_empty_voucher() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF old.voucher_id IS NOT NULL AND EXISTS (SELECT 1 FROM vouchers WHERE id = old.voucher_id) THEN
        IF NOT EXISTS (SELECT 1 FROM postings WHERE voucher_id = old.voucher_id) THEN
            RAISE EXCEPTION 'Voucher % must have at least one posting', old.voucher_id;
        END IF;
    END IF;
    RETURN old;
END;
$$;

CREATE FUNCTION ensure_voucher_has_postings() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM vouchers WHERE id = new.id) THEN
        RETURN new;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM postings WHERE voucher_id = new.id) THEN
        RAISE EXCEPTION 'Voucher % must have at least one posting', new.id;
    END IF;

    RETURN new;
END;
$$;

CREATE FUNCTION normalize_web_address(address text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE RETURN NULLIF(regexp_replace(address, '^(\s|https?://|www\.)+|(\s|/)+$', '', 'gi'), '');

CREATE FUNCTION preserve_a_melding_report_number() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.report_number IS DISTINCT FROM OLD.report_number OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'An A-melding report number and tenant cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reopen_supplier_group_after_member_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM posting_groups WHERE id = OLD.posting_group_id AND tenant_id = OLD.tenant_id;
    RETURN OLD;
END;
$$;

CREATE FUNCTION resolve_brreg_country_id(selected_code text, selected_name text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    selected_id SMALLINT;
BEGIN
    IF selected_code IS NULL AND selected_name IS NULL THEN
        RETURN NULL;
    END IF;
    IF selected_code IS NULL OR selected_name IS NULL THEN
        RAISE EXCEPTION 'Country code and name must both be present';
    END IF;

    SELECT id
    INTO selected_id
    FROM brreg_countries
    WHERE code = selected_code;

    IF selected_id IS NOT NULL THEN
        UPDATE brreg_countries
        SET name = selected_name
        WHERE id = selected_id
          AND name IS DISTINCT FROM selected_name;
        RETURN selected_id;
    END IF;

    INSERT INTO brreg_countries (code, name)
    VALUES (selected_code, selected_name)
    ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name
    RETURNING id INTO selected_id;
    RETURN selected_id;
END;
$$;

CREATE FUNCTION resolve_brreg_industry_id(selected_code text, selected_description text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    selected_id SMALLINT;
BEGIN
    IF selected_code IS NULL AND selected_description IS NULL THEN
        RETURN NULL;
    END IF;
    IF selected_code IS NULL OR selected_description IS NULL THEN
        RAISE EXCEPTION 'Industry code and description must both be present';
    END IF;

    SELECT id
    INTO selected_id
    FROM brreg_industries
    WHERE code = selected_code;

    IF selected_id IS NOT NULL THEN
        UPDATE brreg_industries
        SET description = selected_description
        WHERE id = selected_id
          AND description IS DISTINCT FROM selected_description;
        RETURN selected_id;
    END IF;

    INSERT INTO brreg_industries (code, description)
    VALUES (selected_code, selected_description)
    ON CONFLICT (code) DO UPDATE
    SET description = EXCLUDED.description
    RETURNING id INTO selected_id;
    RETURN selected_id;
END;
$$;

CREATE FUNCTION resolve_brreg_legal_form_id(selected_code text, selected_description text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    selected_id SMALLINT;
BEGIN
    SELECT id
    INTO selected_id
    FROM brreg_legal_forms
    WHERE code = selected_code;

    IF selected_id IS NOT NULL THEN
        UPDATE brreg_legal_forms
        SET description = coalesce(selected_description, description)
        WHERE id = selected_id
          AND description IS DISTINCT FROM coalesce(selected_description, description);
        RETURN selected_id;
    END IF;

    INSERT INTO brreg_legal_forms (code, description)
    VALUES (selected_code, selected_description)
    ON CONFLICT (code) DO UPDATE
    SET description = coalesce(EXCLUDED.description, brreg_legal_forms.description)
    RETURNING id INTO selected_id;
    RETURN selected_id;
END;
$$;

CREATE FUNCTION resolve_brreg_municipality_code(selected_code text, selected_name text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    compact_code SMALLINT;
BEGIN
    IF selected_code IS NULL AND selected_name IS NULL THEN
        RETURN NULL;
    END IF;
    IF selected_code IS NULL OR selected_name IS NULL THEN
        RAISE EXCEPTION 'Municipality code and name must both be present';
    END IF;

    compact_code := selected_code::SMALLINT;
    INSERT INTO brreg_municipalities (code, name)
    VALUES (compact_code, selected_name)
    ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name
    WHERE brreg_municipalities.name IS DISTINCT FROM EXCLUDED.name;
    RETURN compact_code;
END;
$$;

CREATE FUNCTION resolve_brreg_postal_place(selected_code text, selected_name text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    compact_code SMALLINT;
BEGIN
    IF selected_code IS NULL THEN
        RETURN NULL;
    END IF;
    compact_code := selected_code::SMALLINT;
    IF selected_name IS NOT NULL AND length(btrim(selected_name)) > 0 THEN
        INSERT INTO brreg_postal_places (code, name)
        VALUES (compact_code, btrim(selected_name))
        ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
        WHERE brreg_postal_places.category IS NULL AND brreg_postal_places.name IS DISTINCT FROM EXCLUDED.name;
    END IF;
    RETURN compact_code;
END;
$$;

CREATE FUNCTION resolve_brreg_role_kind_id(selected_role_group_code text, selected_role_group_name text, selected_role_code text, selected_role_name text) RETURNS smallint LANGUAGE plpgsql AS $$
DECLARE
    selected_role_kind_id SMALLINT;
    existing_role_group_name TEXT;
    existing_role_name TEXT;
BEGIN
    SELECT id, role_group_name, role_name
    INTO selected_role_kind_id, existing_role_group_name, existing_role_name
    FROM brreg_role_kinds
    WHERE role_group_code = selected_role_group_code
      AND role_code = selected_role_code;

    IF selected_role_kind_id IS NOT NULL THEN
        IF existing_role_group_name IS DISTINCT FROM selected_role_group_name
            OR existing_role_name IS DISTINCT FROM selected_role_name THEN
            UPDATE brreg_role_kinds
            SET role_group_name = selected_role_group_name,
                role_name = selected_role_name
            WHERE id = selected_role_kind_id;
        END IF;
        RETURN selected_role_kind_id;
    END IF;

    INSERT INTO brreg_role_kinds (role_group_code, role_group_name, role_code, role_name)
    VALUES (selected_role_group_code, selected_role_group_name, selected_role_code, selected_role_name)
    ON CONFLICT (role_group_code, role_code) DO UPDATE
    SET role_group_name = EXCLUDED.role_group_name,
        role_name = EXCLUDED.role_name
    RETURNING id INTO selected_role_kind_id;

    RETURN selected_role_kind_id;
END
$$;

CREATE FUNCTION touch_invoice_from_related_row() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    related_invoice_id INTEGER;
    related_tenant_id  INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        related_invoice_id := OLD.invoice_id;
        related_tenant_id := OLD.tenant_id;
    ELSE
        related_invoice_id := NEW.invoice_id;
        related_tenant_id := NEW.tenant_id;
    END IF;

    UPDATE invoices_ar
    SET updated_at = clock_timestamp()
    WHERE id = related_invoice_id
      AND tenant_id = related_tenant_id;

    IF TG_OP = 'UPDATE'
        AND (OLD.invoice_id, OLD.tenant_id) IS DISTINCT FROM (NEW.invoice_id, NEW.tenant_id) THEN
        UPDATE invoices_ar
        SET updated_at = clock_timestamp()
        WHERE id = OLD.invoice_id
          AND tenant_id = OLD.tenant_id;
    END IF;

    RETURN NULL;
END;
$$;

CREATE FUNCTION touch_invoices_from_credit_note_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    new_tenant_id int;
    old_tenant_id int;
    new_invoice_ids int[];
    old_invoice_ids int[];
BEGIN
    IF TG_OP <> 'DELETE' THEN
        new_tenant_id := NEW.tenant_id;
        new_invoice_ids := ARRAY[NEW.invoice_id, NEW.credit_note_invoice_id];
    END IF;
    IF TG_OP <> 'INSERT' THEN
        old_tenant_id := OLD.tenant_id;
        old_invoice_ids := ARRAY[OLD.invoice_id, OLD.credit_note_invoice_id];
    END IF;

    UPDATE invoices_ar
    SET updated_at = clock_timestamp()
    WHERE (tenant_id = new_tenant_id AND id = ANY(new_invoice_ids))
       OR (tenant_id = old_tenant_id AND id = ANY(old_invoice_ids));
    RETURN NULL;
END;
$$;

CREATE FUNCTION touch_invoices_from_posting_group_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    related_posting_group_id INTEGER;
    related_posting_id       BIGINT;
    related_tenant_id        INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        related_posting_group_id := OLD.posting_group_id;
        related_posting_id := OLD.posting_id;
        related_tenant_id := OLD.tenant_id;
    ELSE
        related_posting_group_id := NEW.posting_group_id;
        related_posting_id := NEW.posting_id;
        related_tenant_id := NEW.tenant_id;
    END IF;

    UPDATE invoices_ar invoice
    SET updated_at = clock_timestamp()
    WHERE invoice.tenant_id = related_tenant_id
      AND EXISTS (
        SELECT 1
        FROM postings invoice_posting
        WHERE invoice_posting.voucher_id = invoice.voucher_id
          AND invoice_posting.tenant_id = invoice.tenant_id
          AND (
            invoice_posting.id = related_posting_id
            OR invoice_posting.id IN (
                SELECT grouped_posting.posting_id
                FROM posting_group_postings grouped_posting
                WHERE grouped_posting.posting_group_id = related_posting_group_id
                  AND grouped_posting.tenant_id = related_tenant_id
            )
          )
      );

    IF TG_OP = 'UPDATE'
        AND (OLD.posting_group_id, OLD.posting_id, OLD.tenant_id)
            IS DISTINCT FROM (NEW.posting_group_id, NEW.posting_id, NEW.tenant_id) THEN
        UPDATE invoices_ar invoice
        SET updated_at = clock_timestamp()
        WHERE invoice.tenant_id = OLD.tenant_id
          AND EXISTS (
            SELECT 1
            FROM postings invoice_posting
            WHERE invoice_posting.voucher_id = invoice.voucher_id
              AND invoice_posting.tenant_id = invoice.tenant_id
              AND (
                invoice_posting.id = OLD.posting_id
                OR invoice_posting.id IN (
                    SELECT grouped_posting.posting_id
                    FROM posting_group_postings grouped_posting
                    WHERE grouped_posting.posting_group_id = OLD.posting_group_id
                      AND grouped_posting.tenant_id = OLD.tenant_id
                )
              )
          );
    END IF;

    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_company_type_tenant_rules_for_company() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    company_country companies.country_code%TYPE;
    company_type companies.company_type_code%TYPE;
BEGIN
    SELECT c.country_code, c.company_type_code INTO company_country, company_type
    FROM company_details c JOIN tenants t ON t.company_id = c.id AND t.id = c.tenant_id
    WHERE c.id = NEW.id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF company_type IS NULL THEN
        RAISE EXCEPTION 'Tenant company % must have a company type', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF company_country = 'DK' AND company_type <> 'APS' THEN
        RAISE EXCEPTION 'Danish tenant company % must use company type APS', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION validate_company_type_tenant_rules_for_tenant() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    company_id INTEGER;
    company_country companies.country_code%TYPE;
    company_type companies.company_type_code%TYPE;
BEGIN
    SELECT c.id, c.country_code, c.company_type_code INTO company_id, company_country, company_type
    FROM tenants t JOIN company_details c ON c.id = t.company_id AND c.tenant_id = t.id
    WHERE t.id = NEW.id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF company_type IS NULL THEN
        RAISE EXCEPTION 'Tenant company % must have a company type', company_id USING ERRCODE = 'check_violation';
    END IF;
    IF company_country = 'DK' AND company_type <> 'APS' THEN
        RAISE EXCEPTION 'Danish tenant company % must use company type APS', company_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION validate_invoice_ar_allocation_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        PERFORM public.ensure_invoice_ar_has_source_or_allocation(OLD.credit_note_invoice_id, OLD.tenant_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM public.ensure_invoice_ar_credit_note_allocation_is_valid(NEW.id);
        PERFORM public.ensure_invoice_ar_has_source_or_allocation(NEW.credit_note_invoice_id, NEW.tenant_id);
        PERFORM public.ensure_invoice_ar_credit_note_allocation_is_valid(related_allocation.id)
        FROM public.invoice_ar_credit_note_allocations related_allocation
        WHERE related_allocation.tenant_id = NEW.tenant_id
          AND related_allocation.invoice_id = NEW.credit_note_invoice_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_invoice_ar_source_or_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM public.ensure_invoice_ar_has_source_or_allocation(NEW.id, NEW.tenant_id);
    PERFORM public.ensure_invoice_ar_credit_note_allocation_is_valid(allocation.id)
    FROM public.invoice_ar_credit_note_allocations allocation
    WHERE allocation.tenant_id = NEW.tenant_id
      AND (
          allocation.invoice_id = NEW.id
          OR allocation.credit_note_invoice_id = NEW.id
      );
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_invoice_ar_source_order_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        PERFORM public.ensure_invoice_ar_has_source_or_allocation(OLD.invoice_id, OLD.tenant_id);
        PERFORM public.ensure_invoice_ar_credit_note_allocation_is_valid(allocation.id)
        FROM public.invoice_ar_credit_note_allocations allocation
        WHERE allocation.tenant_id = OLD.tenant_id
          AND (allocation.invoice_id = OLD.invoice_id OR allocation.credit_note_invoice_id = OLD.invoice_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM public.ensure_invoice_ar_has_source_or_allocation(NEW.invoice_id, NEW.tenant_id);
        PERFORM public.ensure_invoice_ar_credit_note_allocation_is_valid(allocation.id)
        FROM public.invoice_ar_credit_note_allocations allocation
        WHERE allocation.tenant_id = NEW.tenant_id
          AND (allocation.invoice_id = NEW.invoice_id OR allocation.credit_note_invoice_id = NEW.invoice_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_product_after_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM validate_product_has_variant(NEW.id, NEW.tenant_id);
    RETURN NULL;
END $$;

CREATE FUNCTION validate_product_after_variant_removal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM validate_product_has_variant(OLD.product_id, OLD.tenant_id);
    RETURN NULL;
END $$;

CREATE FUNCTION validate_product_has_variant(target_product_id integer, target_tenant_id integer) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1
    FROM products p
    WHERE p.id = target_product_id
      AND p.tenant_id = target_tenant_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM product_variants pv
        WHERE pv.product_id = target_product_id
          AND pv.tenant_id = target_tenant_id
    ) THEN
        RAISE EXCEPTION 'Product % for tenant % must have at least one variant', target_product_id, target_tenant_id
            USING ERRCODE = '23514', CONSTRAINT = 'products_require_variant';
    END IF;
END $$;

CREATE FUNCTION validate_site_publication_after_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM validate_site_publications_use_active_product(NEW.product_id, NEW.tenant_id);
    RETURN NULL;
END $$;

CREATE FUNCTION validate_site_publications_after_product_archive() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM validate_site_publications_use_active_product(NEW.id, NEW.tenant_id);
    RETURN NULL;
END $$;

CREATE FUNCTION validate_site_publications_use_active_product(target_product_id integer, target_tenant_id integer) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1
    FROM products p
    WHERE p.id = target_product_id
      AND p.tenant_id = target_tenant_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM products p
        JOIN site_commerce_products scp
          ON scp.product_id = p.id
         AND scp.tenant_id = p.tenant_id
        WHERE p.id = target_product_id
          AND p.tenant_id = target_tenant_id
          AND p.is_archived
          AND scp.published
    ) THEN
        RAISE EXCEPTION 'Archived product % for tenant % cannot be published', target_product_id, target_tenant_id
            USING ERRCODE = '23514', CONSTRAINT = 'site_publications_require_active_product';
    END IF;
END $$;

CREATE FUNCTION validate_supplier_posting_group_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.posting_id, OLD.posting_group_id, OLD.tenant_id)
        IS DISTINCT FROM (NEW.posting_id, NEW.posting_group_id, NEW.tenant_id) THEN
        RAISE EXCEPTION 'Posting group membership must be opened before regrouping';
    END IF;
    PERFORM 1 FROM postings WHERE id = NEW.posting_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    IF TG_TABLE_NAME = 'supplier_posting_group_postings' THEN
        IF NOT EXISTS (
            SELECT 1 FROM postings p
            WHERE p.id = NEW.posting_id AND p.tenant_id = NEW.tenant_id
                AND p.account_number = '2400' AND p.supplier_id IS NOT NULL
        ) OR NOT EXISTS (
            SELECT 1 FROM posting_groups pg
            WHERE pg.id = NEW.posting_group_id AND pg.tenant_id = NEW.tenant_id AND pg.origin = 'LEDGER_CLOSE'
        ) THEN
            RAISE EXCEPTION 'Independent supplier settlement requires a supplier 2400 posting and a ledger close group';
        END IF;
        IF EXISTS (
            SELECT 1 FROM posting_group_postings pgp
            JOIN posting_groups pg ON pg.id = pgp.posting_group_id AND pg.tenant_id = pgp.tenant_id
            WHERE pgp.posting_id = NEW.posting_id AND pgp.tenant_id = NEW.tenant_id AND pg.origin <> 'SALARY_PAYMENT'
        ) THEN
            RAISE EXCEPTION 'Supplier posting is already settled';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM supplier_posting_group_postings spgp
        WHERE spgp.posting_id = NEW.posting_id AND spgp.tenant_id = NEW.tenant_id
    ) AND NOT EXISTS (
        SELECT 1 FROM posting_groups pg
        WHERE pg.id = NEW.posting_group_id AND pg.tenant_id = NEW.tenant_id AND pg.origin = 'SALARY_PAYMENT'
    ) THEN
        RAISE EXCEPTION 'Supplier posting is already settled';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE activities (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code varchar(50) NOT NULL UNIQUE,
    sort_order int NOT NULL,
    is_active bool DEFAULT true NOT NULL
);

CREATE TABLE adyen_recurring_payment_tokens (
    payment_psp_reference varchar(64) PRIMARY KEY,
    stored_payment_method_reference varchar(128) NOT NULL,
    shopper_reference varchar(128) NOT NULL,
    merchant_account varchar(128) NOT NULL,
    received_at timestamptz DEFAULT now() NOT NULL
);

CREATE TABLE adyen_webhook_queue (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    webhook_registration text NOT NULL,
    event_type text NOT NULL,
    resource_id text NOT NULL,
    deduplication_key text NOT NULL,
    payload jsonb NOT NULL,
    processing_state text DEFAULT 'RECEIVED' NOT NULL CHECK (processing_state IN ('RECEIVED', 'LEASED', 'FAILED', 'DEAD')),
    attempt_count smallint DEFAULT 0 NOT NULL CHECK (attempt_count >= 0),
    available_at timestamptz DEFAULT now() NOT NULL,
    lease_expires_at timestamptz,
    last_error text CHECK (last_error IS NULL OR char_length(last_error) <= 1000),
    received_at timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX ON adyen_webhook_queue (processing_state, available_at);

CREATE UNIQUE INDEX ON adyen_webhook_queue (webhook_registration, deduplication_key);

CREATE TABLE bank_bic (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    bic varchar(11) NOT NULL UNIQUE,
    country_code char(2) NOT NULL,
    bank_name varchar(255) NOT NULL,
    clearing_code varchar(34)
);

CREATE INDEX ON bank_bic (country_code);

CREATE TABLE bank_bic_code_map (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    bank_bic_id int NOT NULL REFERENCES bank_bic,
    bank_code_length smallint NOT NULL,
    country_code char(2) NOT NULL,
    bank_code_from varchar(34) NOT NULL,
    bank_code_to varchar(34) NOT NULL,
    CHECK (char_length(bank_code_from) = bank_code_length AND char_length(bank_code_to) = bank_code_length),
    CONSTRAINT bank_bic_code_map_check1 CHECK (bank_code_from <= bank_code_to),
    UNIQUE (country_code, bank_code_from, bank_code_to, bank_bic_id)
);

CREATE INDEX ON bank_bic_code_map (country_code, bank_code_length, bank_code_from, bank_code_to);

CREATE TABLE bounced_email_blocklist (
    created_at timestamptz DEFAULT now() NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    match_type text NOT NULL CHECK (match_type IN ('EMAIL', 'DOMAIN')),
    value text NOT NULL,
    reason text,
    UNIQUE (match_type, value)
);

CREATE UNLOGGED TABLE brreg_companies_stage_import (
    stage_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organisasjonsnummer text,
    navn text,
    organisasjonsform_kode text,
    organisasjonsform_beskrivelse text,
    naeringskode1_kode text,
    naeringskode1_beskrivelse text,
    naeringskode2_kode text,
    naeringskode2_beskrivelse text,
    naeringskode3_kode text,
    naeringskode3_beskrivelse text,
    hjelpeenhetskode_kode text,
    hjelpeenhetskode_beskrivelse text,
    harregistrertantallansatte text,
    antallansatte text,
    registreringsdatoantallansatteenhetsregisteret text,
    registreringsdatoantallansattenavaaregisteret text,
    hjemmeside text,
    epostadresse text,
    telefon text,
    mobil text,
    postadresse_adresse text,
    postadresse_poststed text,
    postadresse_postnummer text,
    postadresse_kommune text,
    postadresse_kommunenummer text,
    postadresse_land text,
    postadresse_landkode text,
    forretningsadresse_adresse text,
    forretningsadresse_poststed text,
    forretningsadresse_postnummer text,
    forretningsadresse_kommune text,
    forretningsadresse_kommunenummer text,
    forretningsadresse_land text,
    forretningsadresse_landkode text,
    institusjonellsektorkode_kode text,
    institusjonellsektorkode_beskrivelse text,
    sisteinnsendteaarsregnskap text,
    registreringsdatoenhetsregisteret text,
    stiftelsesdato text,
    registrertimvaregisteret text,
    registreringsdatomerverdiavgiftsregisteret text,
    registreringsdatomerverdiavgiftsregisteretenhetsregisteret text,
    frivilligmvaregistrertbeskrivelser text,
    registreringsdatofrivilligmerverdiavgiftsregisteret text,
    registrertifrivillighetsregisteret text,
    registreringsdatofrivillighetsregisteret text,
    registrertiforetaksregisteret text,
    registreringsdatoforetaksregisteret text,
    registrertistiftelsesregisteret text,
    registrertipartiregisteret text,
    registreringsdatopartiregisteret text,
    konkurs text,
    konkursdato text,
    underavvikling text,
    underavviklingdato text,
    undertvangsavviklingellertvangsopplosning text,
    tvangsopplostpgamanglendedagliglederdato text,
    tvangsopplostpgamanglenderevisordato text,
    tvangsopplostpgamanglenderegnskapdato text,
    tvangsopplostpgamangelfulltstyredato text,
    tvangsavvikletpgamanglendeslettingdato text,
    overordnetenhet text,
    maalform text,
    vedtektsdato text,
    vedtektsfestetformaal text,
    aktivitet text,
    paategninger text,
    underutenlandskinsolvensbehandlingdato text,
    underrekonstruksjonsforhandlingdato text,
    fravalgrevisjondato text,
    fravalgrevisjonbeslutningsdato text,
    erikonsern text,
    kapital_belop text,
    kapital_antallaksjer text,
    kapital_type text,
    kapital_bundet text,
    kapital_valuta text,
    kapital_innbetalt text,
    kapital_fulltinnbetalt text,
    kapital_innfortdato text,
    registreringsnummerihjemlandet text,
    utenlandskregisternavn text,
    utenlandskregisteradresse_land text,
    utenlandskregisteradresse_poststed text,
    utenlandskregisteradresse_adresse text,
    underlagtlovgivningland text,
    underlagtlovgivninglandkode text,
    foretaksformihjemlandet_kode text,
    foretaksformihjemlandet_beskrivelse text,
    foretaksformihjemlandet_beskrivelsebokmaal text,
    telefon_e164 text,
    mobil_e164 text,
    epostadresse_valid text
);

CREATE TABLE brreg_company_financial_metrics_compact (
    revenue bigint,
    operating_cost bigint,
    ebit bigint,
    net_finance bigint,
    profit_before_tax bigint,
    tax_cost bigint,
    net_profit bigint,
    assets bigint,
    equity bigint,
    debt bigint,
    org_number int NOT NULL,
    fiscal_year smallint NOT NULL,
    PRIMARY KEY (org_number, fiscal_year)
) WITH (autovacuum_vacuum_scale_factor=0.02, autovacuum_vacuum_threshold=5000, autovacuum_analyze_scale_factor=0.01, autovacuum_analyze_threshold=5000);

CREATE TABLE brreg_company_sync_state (
    sync_name text PRIMARY KEY CHECK (sync_name = 'companies-delta'),
    status text NOT NULL CHECK (status IN ('NEVER_RUN', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    last_window_from timestamptz,
    last_window_to timestamptz,
    last_successful_to timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    failed_at timestamptz,
    last_error text,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (last_window_from IS NULL OR last_window_to IS NULL OR last_window_from < last_window_to)
);

CREATE TABLE brreg_countries (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code text NOT NULL CHECK (code ~ '^[A-Z]{2}$') UNIQUE,
    name text NOT NULL CHECK (length(btrim(name)) > 0)
);

CREATE TABLE brreg_industries (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code text NOT NULL CHECK (length(btrim(code)) > 0) UNIQUE,
    description text NOT NULL CHECK (length(btrim(description)) > 0)
);

CREATE TABLE brreg_industry_stats (
    industry text NOT NULL UNIQUE,
    company_count int NOT NULL,
    refreshed_at timestamptz DEFAULT now() NOT NULL,
    industry_code text PRIMARY KEY
);

CREATE INDEX ON brreg_industry_stats (company_count DESC, industry);

CREATE TABLE brreg_legal_forms (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code text NOT NULL CHECK (length(btrim(code)) > 0) UNIQUE,
    description text
);

CREATE TABLE brreg_municipalities (
    code smallint CHECK (code >= 0 AND code <= 9999) PRIMARY KEY,
    name text NOT NULL CHECK (length(btrim(name)) > 0)
);

CREATE TABLE brreg_companies_compact (
    share_capital bigint,
    org_number int CHECK (org_number >= 100000000 AND org_number <= 999999999) PRIMARY KEY,
    employee_count int,
    registered_at date,
    founded_on date,
    vat_registered_on date,
    nav_employer_registered_on date,
    foretak_register_registered_on date,
    volunteer_register_registered_on date,
    dissolution_started_on date,
    bankruptcy_started_on date,
    forced_liquidation_started_on date,
    removed_from_brreg date,
    last_submitted_annual_accounts_year smallint,
    legal_form_id smallint NOT NULL REFERENCES brreg_legal_forms,
    industry_id_1 smallint REFERENCES brreg_industries,
    industry_id_2 smallint REFERENCES brreg_industries,
    industry_id_3 smallint REFERENCES brreg_industries,
    business_municipality_code smallint REFERENCES brreg_municipalities,
    business_country_id smallint REFERENCES brreg_countries,
    postal_code smallint CHECK (postal_code IS NULL OR postal_code >= 0 AND postal_code <= 9999),
    postal_postal_code smallint CHECK (postal_postal_code IS NULL OR postal_postal_code >= 0 AND postal_postal_code <= 9999),
    vat_registered bool,
    foretak_register_registered bool,
    volunteer_register_registered bool,
    is_in_group bool,
    legal_form_description_missing bool NOT NULL,
    name text,
    website text CHECK (website IS NULL OR length(btrim(website)) > 0),
    email text CHECK (email IS NULL OR length(btrim(email)) > 0),
    phone text CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{4,14}$'),
    mobile text CHECK (mobile IS NULL OR mobile ~ '^\+[1-9][0-9]{4,14}$'),
    street_address text,
    city text,
    activity_description text,
    postal_street_address text,
    postal_city text,
    purpose_description text,
    old_name text
) WITH (autovacuum_vacuum_scale_factor=0.02, autovacuum_vacuum_threshold=5000, autovacuum_analyze_scale_factor=0.01, autovacuum_analyze_threshold=5000);

CREATE INDEX ON brreg_companies_compact (business_municipality_code, name, org_number) WHERE business_municipality_code IS NOT NULL;

CREATE INDEX ON brreg_companies_compact (employee_count DESC, name, org_number) WHERE employee_count > 0;

CREATE INDEX ON brreg_companies_compact (industry_id_1, employee_count DESC NULLS LAST, share_capital DESC NULLS LAST, name, org_number);

CREATE INDEX ON brreg_companies_compact (industry_id_1) WHERE industry_id_1 IS NOT NULL AND (bankruptcy_started_on IS NOT NULL OR forced_liquidation_started_on IS NOT NULL OR dissolution_started_on IS NOT NULL);

CREATE INDEX ON brreg_companies_compact (legal_form_id, employee_count DESC NULLS LAST, share_capital DESC NULLS LAST, name, org_number);

CREATE INDEX ON brreg_companies_compact (lower(email)) WHERE email IS NOT NULL;

CREATE INDEX brreg_companies_compact_lower_idx1 ON brreg_companies_compact USING gin (lower(name) gin_trgm_ops);

CREATE INDEX ON brreg_companies_compact (lower(name) text_pattern_ops, org_number) INCLUDE (name, legal_form_id) WHERE removed_from_brreg IS NULL AND name IS NOT NULL;

CREATE INDEX ON brreg_companies_compact (registered_at, name, org_number) WHERE registered_at IS NOT NULL;

CREATE INDEX ON brreg_companies_compact (registered_at DESC NULLS LAST, org_number);

CREATE INDEX ON brreg_companies_compact (share_capital DESC, name, org_number) WHERE share_capital > 0;

ALTER TABLE brreg_companies_compact CLUSTER ON brreg_companies_compact_pkey;

CREATE TABLE brreg_person (
    id int GENERATED BY DEFAULT AS IDENTITY CHECK (id > 0) PRIMARY KEY,
    birth_date date NOT NULL,
    phone_lookup_at timestamptz,
    linkedin_lookup_at timestamptz,
    name_key text NOT NULL CHECK (length(btrim(name_key)) > 0),
    first_name text,
    last_name text,
    middle_name varchar(127),
    phone_number text CHECK (phone_number IS NULL OR length(btrim(phone_number)) > 0),
    linkedin_slug text CHECK (linkedin_slug IS NULL OR length(btrim(linkedin_slug)) > 0),
    CHECK (name_key = NULLIF(lower(regexp_replace(concat_ws(' ', NULLIF(btrim(first_name), ''), NULLIF(btrim(middle_name), ''), NULLIF(btrim(last_name), '')), '[[:space:]]+', ' ', 'g')), ''))
) WITH (autovacuum_vacuum_scale_factor=0.02, autovacuum_vacuum_threshold=5000, autovacuum_analyze_scale_factor=0.01, autovacuum_analyze_threshold=5000);

CREATE INDEX ON brreg_person (id) WHERE phone_lookup_at IS NULL AND NULLIF(phone_number, '') IS NULL;

CREATE INDEX ON brreg_person (linkedin_lookup_at DESC NULLS LAST, id DESC) INCLUDE (name_key, first_name, last_name, linkedin_slug) WHERE linkedin_slug IS NOT NULL AND btrim(linkedin_slug) <> '' AND name_key IS NOT NULL AND btrim(name_key) <> '';

CREATE UNIQUE INDEX ON brreg_person (name_key text_pattern_ops, birth_date);

CREATE INDEX ON brreg_person (name_key, linkedin_lookup_at DESC, id DESC) INCLUDE (first_name, last_name, linkedin_slug) WHERE linkedin_slug IS NOT NULL AND btrim(linkedin_slug) <> '' AND name_key IS NOT NULL AND btrim(name_key) <> '';

CREATE INDEX ON brreg_person (regexp_replace(phone_number, '[[:space:]().-]', '', 'g')) WHERE phone_number IS NOT NULL;

ALTER TABLE brreg_person CLUSTER ON brreg_person_pkey;

CREATE TABLE brreg_postal_places (
    code smallint CHECK (code >= 0 AND code <= 9999) PRIMARY KEY,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    category char(1),
    verified_on date,
    is_current bool DEFAULT true NOT NULL,
    CHECK (category IS NULL AND verified_on IS NULL OR category IS NOT NULL AND category IN ('G', 'P', 'B', 'S') AND verified_on IS NOT NULL)
);

CREATE TABLE brreg_role_kinds (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    role_group_code text NOT NULL CHECK (length(btrim(role_group_code)) > 0),
    role_group_name text,
    role_code text NOT NULL CHECK (length(btrim(role_code)) > 0),
    role_name text,
    UNIQUE (role_group_code, role_code)
);

CREATE TABLE brreg_company_roles_compact (
    person_id int REFERENCES brreg_person,
    org_number int NOT NULL CHECK (org_number >= 100000000 AND org_number <= 999999999),
    holder_org_number int CHECK (holder_org_number IS NULL OR holder_org_number >= 100000000 AND holder_org_number <= 999999999),
    role_kind_id smallint NOT NULL REFERENCES brreg_role_kinds,
    is_active bool DEFAULT true NOT NULL,
    holder_name_override text,
    CHECK (num_nonnulls(holder_org_number, person_id) <= 1)
) WITH (autovacuum_vacuum_scale_factor=0.02, autovacuum_vacuum_threshold=5000, autovacuum_analyze_scale_factor=0.01, autovacuum_analyze_threshold=5000);

CREATE UNIQUE INDEX ON brreg_company_roles_compact (org_number, role_kind_id, holder_org_number, person_id) NULLS NOT DISTINCT;

CREATE INDEX ON brreg_company_roles_compact (person_id) WHERE person_id IS NOT NULL;

CREATE INDEX ON brreg_company_roles_compact (role_kind_id, holder_org_number, org_number) INCLUDE (holder_name_override) WHERE is_active AND holder_org_number IS NOT NULL;

CREATE INDEX ON brreg_company_roles_compact (role_kind_id);

ALTER TABLE brreg_company_roles_compact CLUSTER ON brreg_company_roles_compact_org_number_role_kind_id_holder__idx;

CREATE TABLE brreg_roles_sync_progress (
    sync_name text PRIMARY KEY CHECK (sync_name = 'roles'),
    phase text NOT NULL,
    stage_role_rows bigint NOT NULL,
    stage_companies bigint NOT NULL,
    stage_max_id bigint NOT NULL,
    last_stage_id bigint DEFAULT 0 NOT NULL,
    stale_role_rows bigint,
    last_stale_role_id bigint DEFAULT 0 NOT NULL,
    started_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (stage_role_rows > 0 AND stage_companies > 0 AND stage_max_id > 0 AND last_stage_id >= 0 AND last_stage_id <= stage_max_id AND (stale_role_rows IS NULL OR stale_role_rows >= 0) AND last_stale_role_id >= 0 AND (phase = 'UPSERTING' AND stale_role_rows IS NULL AND last_stale_role_id = 0 OR phase = 'DELETING' AND stale_role_rows IS NOT NULL))
);

CREATE TABLE brreg_sub_entities (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parent_organization_number int NOT NULL CHECK (parent_organization_number >= 100000000 AND parent_organization_number <= 999999999),
    organization_number varchar(9) NOT NULL CHECK (organization_number ~ '^[0-9]{9}$'),
    name varchar(255) NOT NULL,
    address varchar(500),
    postal_code varchar(20),
    city varchar(255),
    municipality_code varchar(4),
    country_code varchar(2),
    industry varchar(255),
    created_at timestamptz DEFAULT now() NOT NULL
);

CREATE UNIQUE INDEX ON brreg_sub_entities (organization_number);

CREATE INDEX ON brreg_sub_entities (parent_organization_number);

CREATE TABLE currency_exchange_rate_import_status (
    id smallint CHECK (id = 1) PRIMARY KEY,
    historical_import_status varchar(20) NOT NULL,
    historical_processed_rows int DEFAULT 0 NOT NULL,
    historical_total_rows int DEFAULT 0 NOT NULL,
    historical_started_at timestamptz,
    historical_completed_at timestamptz,
    last_error_summary varchar(1000),
    last_successful_scheduled_import timestamptz,
    latest_ecb_publication_date date,
    CHECK (historical_processed_rows >= 0 AND historical_total_rows >= 0)
);

CREATE TABLE currency_exchange_rates (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    currency_code varchar(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    publication_date date NOT NULL,
    units_per_eur numeric(20,10) NOT NULL CHECK (units_per_eur > 0),
    UNIQUE (currency_code, publication_date)
);

CREATE INDEX ON currency_exchange_rates (publication_date DESC, currency_code);

CREATE TABLE domain_registration (
    registered_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    registry_synchronized_at timestamptz,
    registry_sync_attempted_at timestamptz,
    next_registry_sync_at timestamptz DEFAULT now() NOT NULL,
    status_changed_at timestamptz DEFAULT now() NOT NULL,
    portfolio_entered_at timestamptz DEFAULT now() NOT NULL,
    registry_sync_failure_count int DEFAULT 0 NOT NULL CHECK (registry_sync_failure_count >= 0),
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    domain_name citext NOT NULL UNIQUE,
    status text DEFAULT 'PENDING' NOT NULL,
    provisioning_transaction_id text,
    registry_sponsoring_registrar_id text,
    registry_sync_error text
);

CREATE INDEX ON domain_registration (expires_at);

CREATE INDEX ON domain_registration (next_registry_sync_at, expires_at) WHERE status <> ALL (ARRAY['TRANSFERRED', 'DELETED']);

CREATE INDEX ON domain_registration (status);

CREATE TABLE domain_registry_operation (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    operation_type text NOT NULL,
    status text NOT NULL,
    domain_registration_id uuid REFERENCES domain_registration ON DELETE SET NULL,
    domain_name citext NOT NULL,
    registry_reference text,
    detail text NOT NULL,
    requested_by_email citext NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    completed_at timestamptz,
    idempotency_key uuid,
    original_expires_at timestamptz,
    resulting_expires_at timestamptz,
    CHECK ((status = 'PENDING') = (completed_at IS NULL)),
    CONSTRAINT domain_registry_operation_check1 CHECK (operation_type <> 'RENEW' OR original_expires_at IS NOT NULL)
);

CREATE INDEX ON domain_registry_operation (created_at DESC);

CREATE UNIQUE INDEX ON domain_registry_operation (domain_name) WHERE status IN ('PENDING', 'RECONCILIATION_REQUIRED') AND operation_type IN ('UPDATE_SUBSCRIBER_CONTACT', 'CHANGE_SUBSCRIBER', 'ASSIGN_TECHNICAL_CONTACT', 'REPLACE_NAMESERVERS');

CREATE INDEX ON domain_registry_operation (domain_registration_id, created_at DESC);

CREATE UNIQUE INDEX ON domain_registry_operation (domain_registration_id, idempotency_key) WHERE operation_type = 'RENEW' AND idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX ON domain_registry_operation (operation_type, domain_name) WHERE status IN ('PENDING', 'RECONCILIATION_REQUIRED') AND operation_type IN ('REGISTER', 'TRANSFER');

CREATE INDEX ON domain_registry_operation (status, created_at DESC) WHERE status IN ('PENDING', 'FAILED', 'RECONCILIATION_REQUIRED');

CREATE TABLE domain_maintenance_operation (
    operation_id uuid REFERENCES domain_registry_operation PRIMARY KEY,
    intent jsonb,
    registry_result jsonb,
    contact_result jsonb,
    assignment_result jsonb,
    assignment_prepared bool DEFAULT false NOT NULL,
    reconciled bool DEFAULT false NOT NULL
);

CREATE TABLE domain_registry_subscriber (
    domain_registration_id uuid REFERENCES domain_registration ON DELETE CASCADE PRIMARY KEY,
    registrant_contact_id text,
    contact_person text NOT NULL,
    organization_name text NOT NULL,
    organization_number text NOT NULL CHECK (organization_number ~ '^[0-9]{9}$'),
    contact_email citext NOT NULL,
    phone text NOT NULL,
    postal_address text NOT NULL,
    postal_code text NOT NULL CHECK (postal_code ~ '^[0-9]{4}$'),
    city text NOT NULL,
    applicant_declaration_version text,
    applicant_declaration_accepted_at timestamptz,
    last_registry_transaction_id text
);

CREATE INDEX ON domain_registry_subscriber (organization_number);

CREATE TABLE domain_subscriber_change_audit (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    domain_registration_id uuid NOT NULL REFERENCES domain_registration ON DELETE CASCADE,
    previous_registrant_contact_id text,
    new_registrant_contact_id text NOT NULL,
    previous_organization_name text,
    previous_organization_number text CHECK (previous_organization_number IS NULL OR previous_organization_number ~ '^[0-9]{9}$'),
    new_organization_name text NOT NULL,
    new_organization_number text NOT NULL CHECK (new_organization_number ~ '^[0-9]{9}$'),
    registry_transaction_id text,
    registry_changed bool NOT NULL,
    changed_by_email citext NOT NULL,
    changed_at timestamptz DEFAULT now() NOT NULL,
    operation_id uuid REFERENCES domain_registry_operation UNIQUE,
    reconciled bool DEFAULT false NOT NULL
);

CREATE INDEX ON domain_subscriber_change_audit (domain_registration_id, changed_at DESC);

CREATE TABLE domain_subscriber_contact_update_audit (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    domain_registration_id uuid NOT NULL REFERENCES domain_registration ON DELETE CASCADE,
    registrant_contact_id text NOT NULL,
    previous_contact_person text NOT NULL,
    previous_contact_email citext NOT NULL,
    previous_phone text,
    previous_postal_address text NOT NULL,
    previous_postal_code text NOT NULL CHECK ("right"(regexp_replace(previous_postal_code, '[^0-9]', '', 'g'), 4) ~ '^[0-9]{4}$'),
    previous_city text NOT NULL,
    new_contact_person text NOT NULL,
    new_contact_email citext NOT NULL,
    new_phone text NOT NULL,
    new_postal_address text NOT NULL,
    new_postal_code text NOT NULL CHECK (new_postal_code ~ '^[0-9]{4}$'),
    new_city text NOT NULL,
    registry_transaction_id text,
    registry_changed bool NOT NULL,
    changed_by_email citext NOT NULL,
    changed_at timestamptz DEFAULT now() NOT NULL,
    operation_id uuid REFERENCES domain_registry_operation UNIQUE,
    reconciled bool DEFAULT false NOT NULL
);

CREATE INDEX ON domain_subscriber_contact_update_audit (domain_registration_id, changed_at DESC);

CREATE TABLE domain_technical_contact_assignment_audit (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    domain_registration_id uuid NOT NULL REFERENCES domain_registration ON DELETE CASCADE,
    previous_registry_contact_ids text[] NOT NULL,
    new_registry_contact_id text NOT NULL,
    registry_transaction_id text,
    registry_changed bool NOT NULL,
    changed_by_email citext NOT NULL,
    changed_at timestamptz DEFAULT now() NOT NULL,
    operation_id uuid REFERENCES domain_registry_operation UNIQUE,
    reconciled bool DEFAULT false NOT NULL
);

CREATE INDEX ON domain_technical_contact_assignment_audit (domain_registration_id, changed_at DESC);

CREATE TABLE freight_postal_zones (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    zone_set varchar(80) NOT NULL,
    postal_from smallint NOT NULL CHECK (postal_from >= 0),
    postal_to smallint NOT NULL,
    terminal smallint NOT NULL,
    zone_in smallint NOT NULL CHECK (zone_in >= 0),
    zone_out smallint NOT NULL CHECK (zone_out >= 0),
    CHECK (postal_to >= postal_from AND postal_to <= 9999),
    UNIQUE (zone_set, postal_from)
);

CREATE TABLE freight_tariffs (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    carrier_code varchar(30) NOT NULL,
    delivery_method varchar(40) NOT NULL,
    zone_set varchar(80) NOT NULL,
    valid_from date NOT NULL,
    review_after date NOT NULL,
    source_reference text NOT NULL,
    agreement bool NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'NOK'),
    max_weight_grams int NOT NULL CHECK (max_weight_grams > 0),
    cubic_cm_per_kg int CHECK (cubic_cm_per_kg > 0),
    weight_step_grams int DEFAULT 1 NOT NULL CHECK (weight_step_grams > 0),
    cost_factor numeric(8,6) NOT NULL CHECK (cost_factor > 0 AND cost_factor <= 1),
    minimum_cost numeric(12,2) DEFAULT 0 NOT NULL CHECK (minimum_cost >= 0),
    bands jsonb NOT NULL CHECK (jsonb_typeof(bands) = 'array'),
    UNIQUE (carrier_code, delivery_method, valid_from),
    CHECK (review_after >= valid_from)
);

CREATE TABLE freight_terminal_zones (
    zone_set varchar(80) NOT NULL,
    terminal_from smallint NOT NULL,
    terminal_to smallint NOT NULL,
    zone smallint NOT NULL CHECK (zone >= 0),
    PRIMARY KEY (zone_set, terminal_from, terminal_to)
);

CREATE TABLE gmail_credentials (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    refresh_token varchar(500) NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE kassasystem_webhook_inbox (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    registration varchar(50) NOT NULL CHECK (length(btrim(registration)) > 0),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    processing_status varchar(20) NOT NULL,
    processing_error text,
    received_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    processed_at timestamptz,
    UNIQUE (registration, payload_hash)
);

CREATE INDEX ON kassasystem_webhook_inbox (processing_status, received_at);

CREATE TABLE mcp_clients (
    client_id varchar(100) PRIMARY KEY,
    client_name varchar(128) NOT NULL,
    redirect_uris text[] NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);

CREATE TABLE norid_lifecycle_message (
    message_id text PRIMARY KEY,
    message_type text NOT NULL,
    domain_name citext,
    new_registrar_id text,
    description text,
    queued_at timestamptz,
    registry_reference text,
    received_at timestamptz DEFAULT now() NOT NULL
);

CREATE INDEX ON norid_lifecycle_message (domain_name, received_at DESC) WHERE domain_name IS NOT NULL;

CREATE TABLE norid_registrar_technical_contact (
    registry_contact_id text NOT NULL UNIQUE,
    role_name text NOT NULL CHECK (length(TRIM(BOTH FROM role_name)) >= 2),
    contact_email citext NOT NULL CHECK (POSITION('@' IN contact_email::text) > 1),
    phone text NOT NULL,
    postal_address text NOT NULL,
    postal_code text NOT NULL CHECK (postal_code ~ '^[0-9]{4}$'),
    city text NOT NULL,
    last_registry_transaction_id text,
    singleton bool DEFAULT true PRIMARY KEY CHECK (singleton)
);

CREATE TABLE peppol_report (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    type varchar(6) NOT NULL CHECK (type IN ('tsr10', 'eusr11')),
    period date NOT NULL CHECK (EXTRACT(day FROM period) = 1),
    created_at timestamptz NOT NULL,
    xml text NOT NULL,
    valid bool NOT NULL,
    sent_at timestamptz,
    send_error text,
    delivered bool,
    CHECK (sent_at IS NULL = (delivered IS NULL))
);

CREATE TABLE peppol_reporting_item (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    exchanged_at timestamptz NOT NULL,
    sending bool NOT NULL,
    c2_id varchar(64) NOT NULL,
    c3_id varchar(64) NOT NULL,
    doc_type_scheme varchar(64) NOT NULL,
    doc_type_value varchar(500) NOT NULL,
    process_scheme varchar(64) NOT NULL,
    process_value varchar(200) NOT NULL,
    transport_protocol varchar(64) NOT NULL,
    c1_country char(2) NOT NULL,
    c4_country char(2),
    end_user_id varchar(256) NOT NULL,
    CHECK (sending OR c4_country IS NOT NULL)
);

CREATE INDEX ON peppol_reporting_item (exchanged_at);

CREATE TABLE reai_system_subscription_plans (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    voucher_limit int NOT NULL,
    price_ex_vat numeric(12,2) NOT NULL,
    additional_voucher_price_ex_vat numeric(12,2),
    currency_code varchar(3) NOT NULL
);

CREATE TABLE sbd (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sender_id varchar(50) NOT NULL,
    receiver_id varchar(50) NOT NULL,
    doc_type varchar(250) NOT NULL,
    process_id varchar(150) NOT NULL,
    created_at timestamptz NOT NULL,
    instance_identifier text,
    forwarding_status varchar(15) NOT NULL CHECK (forwarding_status IN ('PENDING', 'FAILED', 'COMPLETED')),
    forwarding_attempts smallint DEFAULT 0 NOT NULL CHECK (forwarding_attempts >= 0 AND forwarding_attempts <= 5),
    forwarding_lease_until timestamptz,
    forwarded_at timestamptz,
    c2_id varchar(30),
    mls_response_code char(2),
    mls_receiver varchar(100),
    mls_attempts smallint DEFAULT 0 NOT NULL,
    mls_next_attempt_at timestamptz,
    mls_sent_at timestamptz,
    CHECK ((forwarding_status = 'COMPLETED') = (forwarded_at IS NOT NULL))
);

CREATE INDEX ON sbd (id) WHERE forwarding_status = 'PENDING';

CREATE INDEX ON sbd (mls_next_attempt_at) WHERE mls_response_code IS NOT NULL AND mls_sent_at IS NULL;

CREATE UNIQUE INDEX ON sbd (sender_id, instance_identifier);

CREATE TABLE sbd_file (
    sbd_id bigint PRIMARY KEY REFERENCES sbd ON DELETE CASCADE,
    file_data bytea COMPRESSION lz4 NOT NULL
);

CREATE TABLE security_issuers (
    lei varchar(20) CHECK (lei ~ '^[A-Z0-9]{20}$') PRIMARY KEY,
    name text NOT NULL,
    legal_jurisdiction text NOT NULL,
    organization_number varchar(9),
    legal_form text,
    norwegian_resident_assessment bool NOT NULL,
    checked_at timestamptz DEFAULT now() NOT NULL,
    lei_status varchar(20),
    ucits_marketed_in_norway bool DEFAULT false NOT NULL
);

CREATE TABLE security_sources (
    id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    url text NOT NULL CHECK (length(TRIM(BOTH FROM url)) > 0) UNIQUE
);

CREATE TABLE security_instruments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name varchar(150) NOT NULL CHECK (length(TRIM(BOTH FROM name)) > 0),
    isin varchar(12) CHECK (isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$') UNIQUE,
    organization_number varchar(9),
    instrument_type varchar(20) NOT NULL,
    domicile_country varchar(2),
    share_class_currency varchar(3),
    source_id smallint NOT NULL REFERENCES security_sources,
    verified_at timestamptz,
    active bool DEFAULT true NOT NULL,
    issuer_lei varchar(20) REFERENCES security_issuers,
    cfi varchar(6),
    trading_venues text[],
    first_traded_on date,
    fund_categories text[],
    share_savings_account_eligible bool
);

ALTER TABLE security_instruments CLUSTER ON security_instruments_pkey;

CREATE TABLE security_listings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    security_id int NOT NULL REFERENCES security_instruments,
    mic varchar(4) NOT NULL,
    ticker varchar(30) NOT NULL,
    trading_currency varchar(3) NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    source_url text NOT NULL,
    CHECK (valid_to >= valid_from),
    UNIQUE (security_id, mic, ticker, trading_currency, valid_from)
);

CREATE TABLE security_sync_runs (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    status text NOT NULL,
    started_at timestamptz DEFAULT now() NOT NULL,
    completed_at timestamptz,
    instruments int DEFAULT 0 NOT NULL,
    assessed int DEFAULT 0 NOT NULL,
    unresolved int DEFAULT 0 NOT NULL,
    errors jsonb DEFAULT '[]' NOT NULL
);

CREATE TABLE security_tax_facts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    security_id int NOT NULL REFERENCES security_instruments,
    tax_year smallint NOT NULL CHECK (tax_year >= 1900 AND tax_year <= 9999),
    equity_percentage numeric(9,6) CHECK (equity_percentage >= 0),
    normalized_equity_percentage numeric(9,6) CHECK (normalized_equity_percentage >= 0 AND normalized_equity_percentage <= 100),
    corporate_exemption bool,
    evidence_status varchar(20) NOT NULL CHECK (evidence_status IN ('VERIFIED', 'ASSESSED', 'UNRESOLVED')),
    source_url text NOT NULL,
    measured_on date NOT NULL,
    rationale text NOT NULL,
    rule_code text,
    CHECK ((equity_percentage IS NULL AND normalized_equity_percentage IS NULL) <> (corporate_exemption IS NULL))
);

CREATE INDEX ON security_tax_facts (security_id, tax_year, id DESC);

CREATE TABLE shareholder_register_compact (
    number_of_shares bigint,
    org_number int NOT NULL,
    shareholder_org_number int,
    share_percentage_micro int CHECK (share_percentage_micro >= 0 AND share_percentage_micro <= 100000000),
    fiscal_year smallint NOT NULL,
    shareholder_name text
) WITH (autovacuum_vacuum_scale_factor=0.02, autovacuum_vacuum_threshold=5000, autovacuum_analyze_scale_factor=0.01, autovacuum_analyze_threshold=5000);

CREATE UNIQUE INDEX ON shareholder_register_compact (org_number, fiscal_year, COALESCE(shareholder_name, ''), COALESCE(shareholder_org_number, 0)) WITH (fillfactor='100');

CREATE INDEX ON shareholder_register_compact (org_number, fiscal_year DESC) WHERE shareholder_org_number IS NOT NULL;

CREATE INDEX ON shareholder_register_compact (org_number, fiscal_year DESC, share_percentage_micro DESC NULLS LAST, number_of_shares DESC NULLS LAST) WITH (fillfactor='100');

CREATE INDEX ON shareholder_register_compact (shareholder_org_number, org_number, fiscal_year DESC) WHERE shareholder_org_number IS NOT NULL AND share_percentage_micro > 50000000;

CREATE TABLE shopify_compliance_log (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    topic text NOT NULL,
    shop_domain text NOT NULL,
    payload text NOT NULL,
    received_at timestamptz DEFAULT now() NOT NULL,
    handled_at timestamptz,
    handled_note text
);

CREATE INDEX ON shopify_compliance_log (received_at) WHERE handled_at IS NULL;

CREATE INDEX ON shopify_compliance_log (topic);

CREATE TABLE shopify_oauth_state (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    state_token text NOT NULL UNIQUE,
    shop_domain text NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX ON shopify_oauth_state (expires_at);

CREATE TABLE smp_participant (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    participant_id varchar(200) NOT NULL UNIQUE,
    name text NOT NULL,
    country_code char(2) NOT NULL,
    registered_on date DEFAULT CURRENT_DATE NOT NULL,
    geographical_information text
);

CREATE TABLE smp_business_identifier (
    participant_id text NOT NULL REFERENCES smp_participant(participant_id) ON DELETE CASCADE,
    scheme text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (participant_id, scheme, value)
);

CREATE TABLE smp_service (
    participant_id text NOT NULL REFERENCES smp_participant(participant_id) ON DELETE CASCADE,
    document_type_id text NOT NULL,
    process_id text NOT NULL,
    endpoint_url text NOT NULL,
    certificate text NOT NULL,
    service_description text NOT NULL,
    technical_contact_url text NOT NULL,
    PRIMARY KEY (participant_id, document_type_id, process_id)
);

CREATE TABLE supplier_default_cost_accounts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_number varchar(36) NOT NULL UNIQUE,
    account_number varchar(10) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE user_credentials (
    credential_id varchar(1000) PRIMARY KEY,
    user_entity_user_id varchar(1000) NOT NULL,
    public_key bytea NOT NULL,
    signature_count int,
    uv_initialized bool,
    backup_eligible bool NOT NULL,
    authenticator_transports varchar(1000),
    public_key_credential_type varchar(100),
    backup_state bool NOT NULL,
    attestation_object bytea,
    attestation_client_data_json bytea,
    created timestamptz,
    last_used timestamptz,
    label varchar(1000) NOT NULL
);

CREATE INDEX ON user_credentials (user_entity_user_id);

CREATE TABLE tenants (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    company_id int NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    slug varchar(255) NOT NULL,
    is_accountant_company bool DEFAULT false NOT NULL,
    is_auditor_company bool DEFAULT false NOT NULL,
    is_vat_registered bool DEFAULT false NOT NULL,
    ztl_psu_ip inet,
    has_sale_of_products_with_15_percent_vat bool DEFAULT false NOT NULL,
    has_sale_of_products_with_12_percent_vat bool DEFAULT false NOT NULL,
    has_zero_rate_output_vat bool DEFAULT true NOT NULL,
    has_outside_scope_output_vat bool DEFAULT true NOT NULL,
    has_purchase_of_products_with_15_percent_vat bool DEFAULT false NOT NULL,
    shopify_enabled bool DEFAULT false NOT NULL,
    vat_registration_date date,
    is_subscription_exempt bool DEFAULT false NOT NULL,
    customs_declaration_altinn_auto_sync_enabled bool DEFAULT false NOT NULL,
    crm_leads_enabled bool DEFAULT false NOT NULL,
    onboarding_finished bool DEFAULT false NOT NULL,
    project_enabled bool DEFAULT false NOT NULL,
    income_reception_enabled bool DEFAULT false NOT NULL,
    sole_proprietorship_owner_person_identifier varchar(11),
    driving_log_enabled bool DEFAULT false NOT NULL,
    payments_enabled bool DEFAULT false NOT NULL,
    vat_taxation_period_type varchar(20) DEFAULT 'BI_MONTHLY' NOT NULL,
    vat_submission_category varchar(20) DEFAULT 'GENERAL' NOT NULL,
    kassasystem_enabled bool DEFAULT false NOT NULL,
    default_private_payments bool DEFAULT false NOT NULL,
    agriculture_and_forestry_accounts_enabled bool DEFAULT false NOT NULL,
    purchase_order_enabled bool DEFAULT false NOT NULL,
    woocommerce_enabled bool DEFAULT false NOT NULL,
    shipment_enabled bool DEFAULT false NOT NULL,
    online_store_enabled bool DEFAULT false NOT NULL,
    shipping_sender_name varchar(150),
    timesheet_enabled bool DEFAULT true NOT NULL,
    customs_declaration_enabled bool DEFAULT true NOT NULL,
    asset_register_enabled bool DEFAULT true NOT NULL,
    securities_enabled bool DEFAULT true NOT NULL,
    payroll_enabled bool DEFAULT true NOT NULL,
    shipping_carriers jsonb DEFAULT '{}' NOT NULL,
    email_marketing_enabled bool DEFAULT false NOT NULL,
    customer_national_identity_number_enabled bool DEFAULT false NOT NULL,
    loan_enabled bool DEFAULT false NOT NULL,
    warehouse_enabled bool DEFAULT false NOT NULL
);

CREATE UNIQUE INDEX ON tenants (company_id);

CREATE UNIQUE INDEX ON tenants (id, company_id);

CREATE UNIQUE INDEX ON tenants (slug);

CREATE TRIGGER detach_adyen_liable_transactions_before_tenant_delete BEFORE DELETE ON tenants FOR EACH ROW EXECUTE FUNCTION detach_adyen_liable_transactions_before_tenant_delete();

CREATE CONSTRAINT TRIGGER tenants_company_type_rules AFTER INSERT OR UPDATE OF company_id ON tenants DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_company_type_tenant_rules_for_tenant();

CREATE TABLE a_melding_report_counters (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    last_number int NOT NULL CHECK (last_number > 0)
);

CREATE TABLE accountant_client_admin_system_users (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    external_referent varchar(150) NOT NULL,
    status_system_user varchar(150) DEFAULT 'NEW' NOT NULL,
    request_id varchar(150),
    access_package_urn varchar(255) NOT NULL
);

CREATE TABLE accountant_client_engagements (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accountant_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    client_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    description text DEFAULT '' NOT NULL,
    description_updated_at timestamptz,
    description_updated_by_user_id int,
    description_updated_by_name text DEFAULT '' NOT NULL,
    oppdragsansvarlig_user_id int,
    oppdragsansvarlig_name text DEFAULT '' NOT NULL,
    accounting_rules text DEFAULT '' NOT NULL,
    engagement_type varchar(32),
    engagement_scope text DEFAULT '' NOT NULL,
    sales_and_settlements text DEFAULT '' NOT NULL,
    communication_instructions text DEFAULT '' NOT NULL,
    systems jsonb DEFAULT '[]' NOT NULL,
    UNIQUE (accountant_tenant_id, client_tenant_id)
);

CREATE TABLE accountant_client_review_notes (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accountant_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    client_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    note text NOT NULL,
    category text DEFAULT 'GENERAL' NOT NULL,
    created_at timestamptz NOT NULL,
    created_by_user_id int,
    created_by_name text DEFAULT '' NOT NULL,
    updated_at timestamptz,
    updated_by_user_id int,
    updated_by_name text
);

CREATE INDEX ON accountant_client_review_notes (accountant_tenant_id, client_tenant_id, created_at DESC);

CREATE TABLE addresses (
    id int GENERATED BY DEFAULT AS IDENTITY,
    country_iso_code varchar(2) NOT NULL,
    province varchar(50),
    city varchar(40) NOT NULL,
    postal_code varchar(20),
    address_1 varchar(255) NOT NULL,
    address_2 varchar(255),
    recipient_name varchar(255),
    contact_person varchar(255),
    mobile varchar(50),
    email varchar(254),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    PRIMARY KEY (id, tenant_id),
    CHECK (
CASE
    WHEN country_iso_code = 'NO'::bpchar THEN postal_code IS NULL OR postal_code ~ '^[0-9]{4}$'
    ELSE postal_code IS NULL OR length(btrim(postal_code)) > 0
END)
);

CREATE INDEX ON addresses (country_iso_code, postal_code, city, address_1, province, address_2);

CREATE INDEX ON addresses (tenant_id);

CREATE TABLE adyen_onboardings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL UNIQUE,
    company_id int NOT NULL,
    reference text DEFAULT gen_random_uuid()::text NOT NULL,
    organization_number text NOT NULL CHECK (organization_number ~ '^[0-9]{9}$'),
    support_phone_number text,
    website text,
    industry_code text,
    main_legal_entity_id text,
    account_holder_id text,
    account_holder_status text,
    account_holder_updated_at timestamptz,
    business_line_id text,
    business_line_sales_channels text,
    transfer_instrument_id text,
    transfer_instrument_updated_at timestamptz,
    setup_choice text CHECK (setup_choice IS NULL OR setup_choice IN ('ECOM', 'ECOM_POS')),
    pos_store_name text,
    pos_street text,
    pos_street2 text,
    pos_postal_code text,
    pos_city text,
    pos_phone_number text,
    payout_sweep_id text,
    payout_transfer_instrument_id text,
    payout_cadence text CHECK (payout_cadence IS NULL OR payout_cadence = 'DAILY'),
    payout_status text,
    payout_updated_at timestamptz,
    verification_updated_at timestamptz,
    status text DEFAULT 'DRAFT' NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    sole_proprietorship_legal_entity_id text,
    decision_maker_legal_entity_id text,
    agreement_review_pending bool DEFAULT false NOT NULL,
    CHECK (setup_choice IS DISTINCT FROM 'ECOM_POS' OR pos_store_name IS NOT NULL AND pos_street IS NOT NULL AND pos_postal_code IS NOT NULL AND pos_city IS NOT NULL AND pos_phone_number IS NOT NULL),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id, company_id) REFERENCES tenants(id, company_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON adyen_onboardings (account_holder_id) WHERE account_holder_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (business_line_id) WHERE business_line_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (decision_maker_legal_entity_id) WHERE decision_maker_legal_entity_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (main_legal_entity_id) WHERE main_legal_entity_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (payout_sweep_id) WHERE payout_sweep_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (reference);

CREATE UNIQUE INDEX ON adyen_onboardings (sole_proprietorship_legal_entity_id) WHERE sole_proprietorship_legal_entity_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_onboardings (transfer_instrument_id) WHERE transfer_instrument_id IS NOT NULL;

CREATE TABLE adyen_onboarding_capabilities (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    adyen_onboarding_id int NOT NULL,
    capability text NOT NULL,
    requested bool,
    allowed bool,
    enabled bool,
    verification_status text,
    observed_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, adyen_onboarding_id, capability)
);

CREATE TABLE adyen_onboarding_reminders (
    adyen_onboarding_id int NOT NULL,
    tenant_id int NOT NULL,
    claimed_at timestamptz DEFAULT now() NOT NULL,
    sent_at timestamptz,
    PRIMARY KEY (adyen_onboarding_id, tenant_id),
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE adyen_stores (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_onboarding_id int NOT NULL,
    channel text NOT NULL,
    store_id text,
    store_reference text NOT NULL,
    store_status text,
    store_status_observed_at timestamptz,
    business_line_ids text,
    split_configuration_id text,
    split_balance_account_id text,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, adyen_onboarding_id, channel)
);

CREATE UNIQUE INDEX ON adyen_stores (store_id) WHERE store_id IS NOT NULL;

CREATE UNIQUE INDEX ON adyen_stores (store_reference);

CREATE TABLE adyen_payments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_store_id int NOT NULL,
    psp_reference text NOT NULL UNIQUE,
    merchant_reference text,
    payment_link_id text,
    payment_method text,
    amount_value bigint NOT NULL,
    amount_currency text NOT NULL,
    authorised_at timestamptz,
    last_event_code text NOT NULL,
    last_event_at timestamptz NOT NULL,
    additional_data jsonb,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_store_id, tenant_id) REFERENCES adyen_stores(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (id, tenant_id)
);

CREATE INDEX ON adyen_payments (payment_link_id) WHERE payment_link_id IS NOT NULL;

CREATE INDEX ON adyen_payments (tenant_id);

CREATE TABLE adyen_payment_events (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_payment_id int NOT NULL,
    modification_psp_reference text NOT NULL,
    merchant_reference text,
    event_code text NOT NULL,
    success bool NOT NULL,
    amount_value bigint NOT NULL,
    amount_currency text NOT NULL,
    event_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_payment_id, tenant_id) REFERENCES adyen_payments(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (modification_psp_reference, event_code, success)
);

CREATE INDEX ON adyen_payment_events (adyen_payment_id);

CREATE INDEX ON adyen_payment_events (tenant_id);

CREATE TABLE adyen_store_payment_methods (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    adyen_store_id int NOT NULL,
    payment_method_id text,
    payment_method_type text NOT NULL,
    request_status text,
    allowed bool,
    enabled bool,
    verification_status text,
    observed_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_store_id, tenant_id) REFERENCES adyen_stores(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, adyen_store_id, payment_method_type)
);

CREATE TABLE adyen_transfers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    transfer_id text NOT NULL UNIQUE,
    balance_account_id text NOT NULL,
    category text NOT NULL,
    type text,
    status text NOT NULL,
    direction text,
    reference text,
    description text,
    amount_value bigint NOT NULL,
    amount_currency text NOT NULL,
    psp_payment_reference text,
    payment_merchant_reference text,
    adyen_payment_id int,
    created_event_at timestamptz,
    last_event_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    sequence_number int,
    reason text,
    reason_detail text,
    FOREIGN KEY (adyen_payment_id, tenant_id) REFERENCES adyen_payments(id, tenant_id) ON DELETE SET NULL (adyen_payment_id)
);

CREATE INDEX ON adyen_transfers (psp_payment_reference) WHERE psp_payment_reference IS NOT NULL;

CREATE INDEX ON adyen_transfers (tenant_id);

CREATE TABLE adyen_verification_tasks (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    adyen_onboarding_id int NOT NULL,
    capability text NOT NULL,
    entity_id text,
    remediation_code text NOT NULL,
    message text,
    observed_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE,
    UNIQUE NULLS NOT DISTINCT (tenant_id, adyen_onboarding_id, capability, entity_id, remediation_code)
);

CREATE TABLE agent_system_user_client_delegations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accountant_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    client_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    client_organization_number varchar(36) NOT NULL,
    altinn_client_id text,
    updated_at timestamptz DEFAULT now() NOT NULL,
    delegation_status varchar(32) DEFAULT 'ATTACHED' NOT NULL,
    UNIQUE (accountant_tenant_id, client_tenant_id)
);

CREATE INDEX ON agent_system_user_client_delegations (client_tenant_id);

CREATE TABLE agent_system_users (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    external_referent varchar(150) NOT NULL,
    status_system_user varchar(150) DEFAULT 'NEW' NOT NULL,
    request_id varchar(150),
    access_package_urn varchar(255) NOT NULL
);

CREATE TABLE amelding_avstemming_snapshots (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL,
    report_month smallint NOT NULL,
    withholding_tax numeric(15,2) NOT NULL,
    employer_tax numeric(15,2) NOT NULL,
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, report_year, report_month)
);

CREATE TABLE amelding_reconciliation_orders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    order_id varchar(36) NOT NULL,
    creation_expires_at timestamptz DEFAULT CURRENT_TIMESTAMP + '00:05:00' NOT NULL,
    upstream_order_id varchar(36),
    upstream_status varchar(100),
    status varchar(30) DEFAULT 'PENDING' NOT NULL,
    report_year int NOT NULL CHECK (report_year >= 2015),
    from_month int NOT NULL CHECK (from_month >= 1 AND from_month <= 12),
    to_month int NOT NULL,
    pending bool DEFAULT true NOT NULL,
    CHECK (to_month >= from_month AND to_month <= 12),
    UNIQUE (tenant_id, order_id)
);

CREATE UNIQUE INDEX ON amelding_reconciliation_orders (tenant_id, report_year, from_month, to_month) WHERE pending;

CREATE TABLE amelding_reconciliation_snapshots (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL,
    from_month smallint NOT NULL,
    to_month smallint NOT NULL,
    report_data jsonb NOT NULL,
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, report_year, from_month, to_month),
    CHECK (((from_month >= 1) AND (from_month <= 12) AND ((to_month >= from_month) AND (to_month <= 12))))
);

CREATE TABLE amelding_reporting_observations (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reporting_period date NOT NULL CHECK (EXTRACT(day FROM reporting_period) = 1),
    organization_number varchar(9) NOT NULL,
    employee_count_registered_on date NOT NULL,
    registry_synced_through timestamptz NOT NULL,
    observed_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, reporting_period)
);

CREATE TABLE annual_account_note_drafts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    notes jsonb NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (tenant_id, fiscal_year)
);

CREATE TABLE app_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    actor varchar NOT NULL,
    fingerprint char(64) NOT NULL,
    resource_id int NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (tenant_id, request_id)
);

COMMENT ON COLUMN app_submissions.resource_id IS 'Retained command receipt: expense or reception document id, even after the resource is processed or deleted.';

CREATE TABLE attachments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    file_data bytea COMPRESSION lz4 NOT NULL,
    filename varchar(255) NOT NULL,
    mimetype varchar(255) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    PRIMARY KEY (id, tenant_id)
) WITH (autovacuum_vacuum_scale_factor=0.05, toast.autovacuum_vacuum_scale_factor=0.02);

CREATE INDEX ON attachments (tenant_id);

CREATE TABLE annual_account_auditor_documents (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE
);

CREATE INDEX ON annual_account_auditor_documents (attachment_id, tenant_id);

CREATE UNIQUE INDEX ON annual_account_auditor_documents (tenant_id, fiscal_year);

CREATE TABLE annual_account_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    altinn_instance_id varchar(255),
    status varchar(30) NOT NULL,
    complete_at timestamptz,
    signed_at timestamptz,
    approver varchar(255),
    email varchar(255) CHECK (email IS NULL OR email = lower(email)) CONSTRAINT annual_account_submissions_email_check1 CHECK (email IS NULL OR length(btrim(email)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    attachment_id int,
    submitted_in_other_system bool DEFAULT false NOT NULL,
    altinn_status_checked_at timestamptz,
    parent_company bool,
    consolidated_accounts bool,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE
);

CREATE INDEX ON annual_account_submissions (tenant_id, fiscal_year, created_at DESC);

CREATE UNIQUE INDEX ON annual_account_submissions (tenant_id, fiscal_year) WHERE status = 'SIGNING';

CREATE TABLE companies (
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    address_id int,
    founded_date date,
    created_from_brreg bool DEFAULT false NOT NULL,
    organization_number varchar(36),
    name varchar(255),
    country_code char(2) NOT NULL CHECK (btrim(country_code) <> ''),
    company_type_code varchar(6),
    municipality_code varchar(4),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    registry_org_number int REFERENCES brreg_companies_compact,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (address_id, tenant_id) REFERENCES addresses,
    CHECK (name IS NOT NULL OR registry_org_number IS NOT NULL),
    CONSTRAINT companies_check1 CHECK (country_code <> 'NO' OR organization_number IS NULL OR btrim(organization_number) ~ '^[0-9]{9}$'),
    CONSTRAINT companies_check2 CHECK (registry_org_number IS NULL OR country_code = 'NO' AND organization_number IS NOT NULL AND organization_number = registry_org_number::text)
);

CREATE INDEX ON companies (address_id);

CREATE INDEX ON companies (registry_org_number) WHERE registry_org_number IS NOT NULL;

CREATE INDEX ON companies (tenant_id);

CREATE UNIQUE INDEX ON companies (tenant_id, organization_number, country_code) WHERE organization_number IS NOT NULL AND organization_number <> '';

CREATE CONSTRAINT TRIGGER companies_company_type_tenant_rules AFTER INSERT OR UPDATE OF country_code, company_type_code, registry_org_number ON companies DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_company_type_tenant_rules_for_company();

CREATE TABLE company_bank (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    sync_enabled bool DEFAULT true NOT NULL,
    last_synced_at timestamptz,
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$' OR currency IS NULL),
    provider_type varchar(50) NOT NULL,
    name varchar(255) NOT NULL,
    provider_account_id varchar(255),
    provider_balance numeric(15,2),
    provider_bank_id int,
    iban varchar(34) CHECK (iban IS NULL OR length(btrim(iban)) > 0),
    is_archived bool DEFAULT false NOT NULL,
    bank_bic_id int REFERENCES bank_bic,
    country_code char(2) NOT NULL,
    bank_code varchar(20) DEFAULT '' NOT NULL,
    account_number varchar(30) DEFAULT '' NOT NULL,
    default_for_outgoing_payment bool DEFAULT false NOT NULL,
    reconciliation_balance numeric(15,2),
    exclude_from_reconciliation_todos bool DEFAULT false NOT NULL,
    default_for_receipt_reception_manual_payment bool DEFAULT false NOT NULL,
    is_private bool DEFAULT false NOT NULL,
    reconciliation_start_date date,
    ledger_account_number varchar(4) DEFAULT '1920' NOT NULL,
    PRIMARY KEY (id, tenant_id),
    CHECK (ledger_account_number = '1920' OR ledger_account_number = '2380' AND provider_type = 'MANUAL' AND NOT is_private),
    UNIQUE (provider_account_id, currency, iban),
    UNIQUE (tenant_id, name)
);

CREATE INDEX ON company_bank (iban) WHERE iban IS NOT NULL;

CREATE UNIQUE INDEX ON company_bank (tenant_id, country_code, bank_code, account_number, currency) WHERE bank_code <> '' AND account_number <> '';

CREATE UNIQUE INDEX ON company_bank (tenant_id, currency) WHERE default_for_outgoing_payment = true;

CREATE UNIQUE INDEX ON company_bank (tenant_id) WHERE default_for_receipt_reception_manual_payment = true;

CREATE TABLE bank_reconciliation_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    company_bank_id int NOT NULL,
    reconciliation_month date NOT NULL,
    attachment_id int NOT NULL,
    tenant_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank ON DELETE CASCADE
);

CREATE INDEX ON bank_reconciliation_attachments (company_bank_id, reconciliation_month, tenant_id);

CREATE TABLE contact_number_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    enabled bool DEFAULT false NOT NULL,
    next_customer_number int DEFAULT 1 NOT NULL CHECK (next_customer_number > 0),
    next_supplier_number int DEFAULT 1 NOT NULL CHECK (next_supplier_number > 0)
);

CREATE TABLE creditors (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    archived bool DEFAULT false NOT NULL,
    bank_account_number varchar(50) CHECK (bank_account_number IS NULL OR length(btrim(bank_account_number)) > 0),
    PRIMARY KEY (id, tenant_id)
);

CREATE INDEX ON creditors (tenant_id, archived);

CREATE TABLE crm_saved_lead_filters (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    filter_config text NOT NULL CHECK (length(btrim(filter_config)) > 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE customs_declaration_sequences (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year smallint NOT NULL,
    next_number int NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE debtors (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    archived bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

CREATE INDEX ON debtors (tenant_id, archived);

CREATE TABLE departments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(150) NOT NULL,
    archived bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

CREATE TABLE dhl_shipping_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    duty_payer varchar(9) DEFAULT 'STORE' NOT NULL CHECK (duty_payer IN ('STORE', 'RECIPIENT'))
);

CREATE TABLE dhl_tenant_agreements (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    environment varchar(10) NOT NULL CHECK (environment IN ('TEST', 'PRODUCTION')),
    account_number varchar(9) NOT NULL CHECK (account_number ~ '^[0-9]{9}$'),
    api_username_ciphertext text CHECK (length(api_username_ciphertext) > 0),
    api_password_ciphertext text CHECK (length(api_password_ciphertext) > 0),
    active bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    import_account_number varchar(9) CHECK (import_account_number IS NULL OR import_account_number ~ '^[0-9]{9}$'),
    uses_shared_api bool DEFAULT false NOT NULL,
    shared_agreement_id varchar(40),
    CHECK (shared_agreement_id IS NULL AND api_username_ciphertext IS NOT NULL AND api_password_ciphertext IS NOT NULL OR shared_agreement_id IS NOT NULL AND shared_agreement_id ~ '^[a-z0-9-]{1,40}$' AND uses_shared_api AND api_username_ciphertext IS NULL AND api_password_ciphertext IS NULL),
    UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ON dhl_tenant_agreements (tenant_id, environment) WHERE active;

CREATE TABLE documents (
    id int GENERATED BY DEFAULT AS IDENTITY,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    is_archived bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE
);

CREATE INDEX ON documents (created_at);

CREATE INDEX ON documents (tenant_id);

CREATE TABLE agreements (
    id int GENERATED BY DEFAULT AS IDENTITY,
    template_type varchar(50) NOT NULL,
    signed bool DEFAULT false NOT NULL,
    signer_email varchar(255),
    signed_at timestamptz,
    document_id int,
    created_at timestamptz NOT NULL,
    edited_at timestamptz,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (document_id, tenant_id) REFERENCES documents ON DELETE SET NULL (document_id)
);

CREATE INDEX ON agreements (created_at DESC);

CREATE UNIQUE INDEX ON agreements (document_id) WHERE document_id IS NOT NULL;

CREATE INDEX ON agreements (signed);

CREATE INDEX ON agreements (template_type);

CREATE INDEX ON agreements (tenant_id);

CREATE TABLE agreement_accounting_services (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agreement_id int NOT NULL UNIQUE,
    client_entity_type varchar(50),
    client_company_name varchar(255),
    client_org_number varchar(50),
    client_address varchar(255),
    client_contact_person varchar(255),
    client_email varchar(255),
    client_phone varchar(50) CHECK (client_phone IS NULL OR client_phone ~ '^\+[1-9][0-9]{4,14}$'),
    service_bookkeeping bool DEFAULT false NOT NULL,
    service_reconciliation bool DEFAULT false NOT NULL,
    service_vat bool DEFAULT false NOT NULL,
    service_payroll bool DEFAULT false NOT NULL,
    service_pension bool DEFAULT false NOT NULL,
    service_invoicing bool DEFAULT false NOT NULL,
    service_annual_accounts bool DEFAULT false NOT NULL,
    service_consulting bool DEFAULT false NOT NULL,
    service_other text,
    start_date date,
    pricing_model varchar(50),
    fixed_price_yearly numeric(10,2),
    hourly_rate numeric(10,2),
    combination_fixed numeric(10,2),
    combination_hourly numeric(10,2),
    billing_frequency varchar(50),
    billing_frequency_other varchar(100),
    payment_terms_days int,
    notice_period_months int,
    in_english bool DEFAULT false NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    additional_hourly_rate numeric(10,2) DEFAULT 750,
    additional_hourly_rate_currency varchar(3) DEFAULT 'NOK',
    prepayment bool DEFAULT true NOT NULL,
    other_agreed_terms text,
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE CASCADE
);

CREATE INDEX ON agreement_accounting_services (tenant_id);

CREATE TABLE agreement_employee_contract (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agreement_id int NOT NULL UNIQUE,
    employer_company_name varchar(255),
    employer_org_number varchar(50),
    employer_address varchar(255),
    employer_contact_person varchar(255),
    employer_email varchar(255),
    employer_phone varchar(50) CHECK (employer_phone IS NULL OR employer_phone ~ '^\+[1-9][0-9]{4,14}$'),
    employee_name varchar(255),
    employee_address varchar(255),
    employee_email varchar(255),
    employee_phone varchar(50) CHECK (employee_phone IS NULL OR employee_phone ~ '^\+[1-9][0-9]{7,14}$'),
    job_title varchar(255),
    employment_type varchar(50),
    start_date date,
    end_date date,
    working_hours_percent int,
    salary_type varchar(50),
    salary_amount numeric(10,2),
    notice_period_months int,
    in_english bool DEFAULT false NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    workplace varchar(255),
    other_terms text,
    otp_enabled bool DEFAULT true NOT NULL,
    otp_pension_provider varchar(255),
    payout_day_of_month smallint DEFAULT 5 CHECK (payout_day_of_month IS NULL OR payout_day_of_month >= 1 AND payout_day_of_month <= 31),
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE CASCADE
);

CREATE INDEX ON agreement_employee_contract (tenant_id);

CREATE TABLE agreement_purchase_agreement (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agreement_id int NOT NULL UNIQUE,
    seller_name varchar(255),
    seller_org_or_birth_number varchar(50),
    seller_address varchar(255),
    seller_email varchar(255),
    seller_phone varchar(50) CHECK (seller_phone IS NULL OR seller_phone ~ '^\+[1-9][0-9]{4,14}$'),
    buyer_name varchar(255),
    buyer_org_or_birth_number varchar(50),
    buyer_address varchar(255),
    buyer_email varchar(255),
    buyer_phone varchar(50) CHECK (buyer_phone IS NULL OR buyer_phone ~ '^\+[1-9][0-9]{4,14}$'),
    object_description text,
    purchase_price numeric(12,2),
    payment_terms text,
    handover_date date,
    other_terms text,
    in_english bool DEFAULT false NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    vat_treatment varchar(20) DEFAULT 'EXCLUSIVE' NOT NULL,
    binding_method varchar(32) DEFAULT 'SIGNATURE_OR_EMAIL' NOT NULL,
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE CASCADE
);

CREATE INDEX ON agreement_purchase_agreement (tenant_id);

CREATE TABLE agreement_rent_agreement (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agreement_id int NOT NULL UNIQUE,
    landlord_name varchar(255),
    landlord_birth_date_or_org_number varchar(50),
    landlord_address varchar(255),
    landlord_postal_code_and_city varchar(255),
    landlord_email varchar(255),
    landlord_phone varchar(50) CHECK (landlord_phone IS NULL OR landlord_phone ~ '^\+[1-9][0-9]{4,14}$'),
    tenant_name varchar(255),
    tenant_birth_date date,
    tenant_address varchar(255),
    tenant_postal_code_and_city varchar(255),
    tenant_email varchar(255),
    tenant_phone varchar(50) CHECK (tenant_phone IS NULL OR tenant_phone ~ '^\+[1-9][0-9]{4,14}$'),
    tenant_representative_name varchar(255),
    tenant_representative_birth_date date,
    shared_area_share_percent int,
    property_address varchar(255),
    property_municipality varchar(255),
    property_gnr varchar(50),
    property_bnr varchar(50),
    property_section_number varchar(50),
    property_unit_number varchar(50),
    rent_object_type varchar(50),
    room_count int,
    storage_count int,
    parking_space_count int,
    access_details text,
    furnished bool DEFAULT false NOT NULL,
    special_conditions text,
    inventory_list_attached bool DEFAULT false NOT NULL,
    electricity_terms varchar(50),
    electricity_monthly_advance numeric(10,2),
    electricity_own_subscription_from_date date,
    electricity_share_percent int,
    water_terms varchar(50),
    water_monthly_advance numeric(10,2),
    water_share_percent int,
    cable_tv_included bool DEFAULT false NOT NULL,
    internet_included bool DEFAULT false NOT NULL,
    monthly_rent numeric(10,2),
    rent_due_day_of_month int,
    rent_account_number varchar(50),
    radon_measured bool DEFAULT false NOT NULL,
    radon_measured_at date,
    radon_level_becquerel int,
    energy_certificate_exists bool DEFAULT false NOT NULL,
    lease_duration_type varchar(50),
    lease_start_date date,
    lease_start_time varchar(10),
    lease_end_date date,
    lease_end_time varchar(10),
    short_fixed_term_reason_type varchar(50),
    special_fixed_term_reason text,
    tenant_notice_months int,
    landlord_notice_months int,
    deposit_type varchar(50),
    deposit_amount numeric(10,2),
    deposit_account_number varchar(50),
    guarantee_amount numeric(10,2),
    guarantee_issuer varchar(255),
    quiet_hours_from varchar(10),
    quiet_hours_to varchar(10),
    pets_allowed bool DEFAULT false NOT NULL,
    pets_details varchar(255),
    smoking_allowed bool DEFAULT false NOT NULL,
    house_rules text,
    allow_paint_walls bool DEFAULT false NOT NULL,
    allow_paint_ceilings bool DEFAULT false NOT NULL,
    allow_replace_floors bool DEFAULT false NOT NULL,
    allow_paint_kitchen_fronts bool DEFAULT false NOT NULL,
    allow_replace_kitchen_fronts bool DEFAULT false NOT NULL,
    allow_hang_shelves bool DEFAULT false NOT NULL,
    allow_hang_tv bool DEFAULT false NOT NULL,
    allow_universal_design bool DEFAULT false NOT NULL,
    allowed_customizations_other text,
    other_terms text,
    attachment_count int,
    copy_count int,
    in_english bool DEFAULT false NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE CASCADE
);

CREATE INDEX ON agreement_rent_agreement (tenant_id);

CREATE TABLE agreement_service_agreement (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agreement_id int NOT NULL UNIQUE,
    client_entity_type varchar(50),
    client_company_name varchar(255),
    client_org_number varchar(50),
    client_address varchar(255),
    client_contact_person varchar(255),
    client_email varchar(255),
    client_phone varchar(50) CHECK (client_phone IS NULL OR client_phone ~ '^\+[1-9][0-9]{4,14}$'),
    service_description text,
    start_date date,
    end_date date,
    hourly_rate numeric(10,2),
    fixed_price numeric(10,2),
    billing_frequency varchar(50),
    billing_frequency_other varchar(100),
    payment_terms_days int,
    expenses_paid_by_client bool DEFAULT false NOT NULL,
    notice_period_months int,
    other_terms text,
    in_english bool DEFAULT false NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    issuer_role varchar(20) DEFAULT 'SUPPLIER' NOT NULL,
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE CASCADE
);

CREATE INDEX ON agreement_service_agreement (tenant_id);

CREATE TABLE driving_log_vehicles (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    registration_number varchar(30) NOT NULL CHECK (length(TRIM(BOTH FROM registration_number)) > 0),
    name varchar(100) NOT NULL CHECK (length(TRIM(BOTH FROM name)) > 0),
    accounting_mode varchar(30) NOT NULL CHECK (accounting_mode IN ('MILEAGE_ALLOWANCE', 'LOG_ONLY')),
    archived bool DEFAULT false NOT NULL,
    version int DEFAULT 0 NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, registration_number)
);

CREATE TABLE email_marketing_segment (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name text NOT NULL,
    tag text,
    customer_type text DEFAULT 'all' NOT NULL CHECK (customer_type IN ('all', 'business', 'private')),
    built_in text,
    archived_at timestamptz,
    min_campaigns_received int CHECK (min_campaigns_received >= 0),
    max_campaigns_received int CHECK (max_campaigns_received >= 0),
    country_code varchar(2),
    exclude_country bool DEFAULT false NOT NULL,
    CHECK (built_in IS NULL OR tag IS NULL AND min_campaigns_received IS NULL AND max_campaigns_received IS NULL AND (country_code IS NULL AND NOT exclude_country AND (customer_type = 'all' AND built_in IN ('all_subscribed', 'all_unsubscribed', 'no_campaigns_received', 'no_orders', 'one_order', 'repeat_customers', 'ordered_last_30_days', 'no_order_last_180_days') OR built_in = 'private_subscribed' AND customer_type = 'private' OR built_in = 'business_subscribed' AND customer_type = 'business') OR customer_type = 'all' AND country_code IS NOT NULL AND country_code = 'NO' AND (built_in IN ('norway', 'norway_no_campaigns_received') AND NOT exclude_country OR built_in = 'not_norway' AND exclude_country))),
    CONSTRAINT email_marketing_segment_check1 CHECK (country_code IS NULL AND NOT exclude_country OR country_code IS NOT NULL AND country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT email_marketing_segment_check2 CHECK (min_campaigns_received IS NULL OR max_campaigns_received IS NULL OR min_campaigns_received <= max_campaigns_received),
    UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ON email_marketing_segment (tenant_id, built_in);

CREATE TABLE email_marketing_campaign (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name text NOT NULL,
    segment_id int NOT NULL,
    subject text NOT NULL,
    body text NOT NULL,
    status text DEFAULT 'draft' NOT NULL CHECK (status IN ('draft', 'sending', 'completed', 'cancelled')),
    sender_address text,
    sender_name text,
    created_at timestamptz DEFAULT now() NOT NULL,
    launched_at timestamptz,
    body_format text DEFAULT 'text' NOT NULL CHECK (body_format IN ('text', 'html')),
    discarded_at timestamptz,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (segment_id, tenant_id) REFERENCES email_marketing_segment(id, tenant_id)
);

CREATE TABLE email_outbox (
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    next_attempt_at timestamptz,
    lease_until timestamptz,
    completed_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sender_tenant_id int REFERENCES tenants ON DELETE SET NULL,
    rerouted_from_id int REFERENCES email_outbox ON DELETE SET NULL,
    attempt_count smallint DEFAULT 0 NOT NULL,
    status text NOT NULL,
    transport text NOT NULL,
    sender_address text,
    sender_name text,
    to_addresses text NOT NULL,
    cc_addresses text,
    bcc_addresses text,
    reply_to text,
    subject text NOT NULL,
    html text,
    provider_message_id text,
    last_error text,
    attachments jsonb,
    bud_deliveries jsonb,
    text_body text,
    delivery_policy varchar(40),
    prepared_at timestamptz
);

CREATE UNIQUE INDEX ON email_outbox (id, sender_tenant_id);

CREATE INDEX ON email_outbox (next_attempt_at) WHERE status IN ('PENDING', 'QUEUED');

CREATE TABLE holiday_allowance_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    scheme varchar(32) NOT NULL
);

CREATE TABLE inntektsmelding_attempts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    nav_reference_id varchar(64) NOT NULL,
    attempt_token uuid DEFAULT gen_random_uuid() NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('REVIEW', 'SENDING', 'UNCERTAIN', 'SUBMITTED', 'APPROVED', 'REJECTED', 'FAILED')),
    request_json text NOT NULL,
    previous_receipt_ids text DEFAULT '[]' NOT NULL,
    submitted_by varchar(255) NOT NULL,
    innsending_id varchar(64),
    review_reason varchar(32),
    notified_state varchar(48),
    updated_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (tenant_id, nav_reference_id)
);

CREATE TABLE inntektsmelding_forespoersel_notifications (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    nav_reference_id varchar(64) NOT NULL,
    notified_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (tenant_id, nav_reference_id)
);

CREATE TABLE inntektsmelding_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    enabled bool DEFAULT false NOT NULL,
    contact_name varchar(64) DEFAULT '' NOT NULL,
    employer_phone varchar(32) DEFAULT '' NOT NULL,
    refund_policy varchar(10) DEFAULT 'UNKNOWN' NOT NULL CHECK (refund_policy IN ('UNKNOWN', 'NONE', 'FULL')),
    normal_employer_period bool DEFAULT false NOT NULL,
    benefits_continue bool DEFAULT false NOT NULL
);

CREATE TABLE inntektsmelding_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    nav_reference_id varchar(64) NOT NULL,
    innsending_id varchar(64) NOT NULL,
    employee_ssn varchar(11) NOT NULL,
    monthly_income numeric(15,2),
    refund_amount_per_month numeric(15,2),
    status varchar(20) DEFAULT 'MOTTATT' NOT NULL CHECK (status IN ('MOTTATT', 'FEILET', 'GODKJENT')),
    validation_error text,
    submitted_by varchar(255) NOT NULL,
    submitted_at timestamptz DEFAULT now() NOT NULL,
    status_checked_at timestamptz
);

CREATE UNIQUE INDEX ON inntektsmelding_submissions (tenant_id, innsending_id);

CREATE INDEX ON inntektsmelding_submissions (tenant_id, submitted_at DESC);

CREATE TABLE invoice_ar_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    days_until_due smallint NOT NULL CHECK (days_until_due >= 0),
    automatic_reminder_include_fee bool DEFAULT false NOT NULL,
    automatic_reminder_include_late_payment_interest bool DEFAULT false NOT NULL,
    automatic_reminder_enabled_for_all_customers bool DEFAULT true NOT NULL,
    automatic_reminder_go_straight_to_debt_collection bool DEFAULT true NOT NULL,
    show_invoice_references bool DEFAULT false NOT NULL,
    default_our_reference varchar(255),
    default_buyer_reference varchar(255),
    show_delivery_address_and_date bool DEFAULT false NOT NULL,
    reminder_cc_email varchar(255),
    reminder_bcc_email varchar(255),
    automatic_debt_collection_enabled bool DEFAULT true NOT NULL,
    automatic_reminder_days_after_due int DEFAULT 1 NOT NULL CHECK (automatic_reminder_days_after_due >= 1 AND automatic_reminder_days_after_due <= 365),
    automatic_debt_collection_days_after_due int DEFAULT 2 NOT NULL CHECK (automatic_debt_collection_days_after_due >= 1 AND automatic_debt_collection_days_after_due <= 365),
    automatic_reminder_due_days int DEFAULT 14 NOT NULL CHECK (automatic_reminder_due_days >= 14 AND automatic_reminder_due_days <= 365),
    order_confirmation_cc_email varchar(255),
    order_confirmation_bcc_email varchar(255),
    order_line_grouping_enabled bool DEFAULT false NOT NULL,
    show_purchase_order_reference bool DEFAULT false NOT NULL,
    show_seller_employee bool DEFAULT false NOT NULL,
    order_confirmation_packing_list_enabled bool DEFAULT false NOT NULL
);

CREATE TABLE invoice_receiver_bank (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    currency_code varchar(3) NOT NULL,
    company_bank_id int NOT NULL,
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    UNIQUE (tenant_id, currency_code)
);

CREATE TABLE invoice_sequences (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year smallint NOT NULL,
    next_number int NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE kassasystem_daily_budgets (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    business_date date NOT NULL,
    amount numeric(14,2) NOT NULL CHECK (amount >= 0),
    UNIQUE (tenant_id, business_date)
);

CREATE TABLE kassasystem_mobile_push_devices (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    device_id varchar(100) NOT NULL,
    operator_id varchar(100) NOT NULL,
    token varchar(512) NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, device_id)
);

CREATE TABLE kassasystem_retired_mobile_requests (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    event_type varchar(30) NOT NULL CHECK (event_type IN ('SALE', 'RETURN')),
    reference varchar(100) NOT NULL CHECK (length(btrim(reference)) > 0),
    retired_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, event_type, reference)
);

CREATE TABLE kassasystem_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    amount_entry_enabled bool DEFAULT true NOT NULL,
    tips_enabled bool DEFAULT true NOT NULL,
    discounts_enabled bool DEFAULT true NOT NULL,
    cash_payments_enabled bool DEFAULT true NOT NULL,
    barcode_scanning_enabled bool DEFAULT true NOT NULL,
    gift_cards_enabled bool DEFAULT true NOT NULL
);

COMMENT ON COLUMN kassasystem_settings.gift_cards_enabled IS 'Whether the till offers gift cards as payment, for tenants that sell them but not in the shop.';

CREATE TABLE kravia_creditor_connections (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    creditor_id int NOT NULL,
    organization_number varchar(20) NOT NULL,
    creditor_name varchar(200),
    approval_status varchar(50),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, creditor_id)
);

CREATE TABLE locked_periods (
    created_at timestamptz DEFAULT now() NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    from_date date NOT NULL,
    to_date date NOT NULL,
    company_bank_id int,
    type varchar(30) DEFAULT 'ACCOUNTING' NOT NULL,
    CHECK (from_date <= to_date),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank
);

CREATE TABLE manual_reconciliation_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    analysis_guidance text DEFAULT '' NOT NULL CHECK (length(analysis_guidance) <= 4000)
);

CREATE TABLE maskinporten_system_users (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    external_referent varchar(150) NOT NULL,
    status_system_user varchar(150) DEFAULT 'NEW' NOT NULL,
    request_id varchar(150)
);

CREATE TABLE offer_number_series (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    prefix varchar(20) NOT NULL,
    next_number int NOT NULL CHECK (next_number > 0)
);

CREATE TABLE offer_sequences (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year smallint NOT NULL,
    next_number int NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE order_number_series (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    prefix varchar(20) NOT NULL,
    next_number int NOT NULL CHECK (next_number > 0)
);

CREATE TABLE order_sequences (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year smallint NOT NULL,
    next_number int NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE payment_approval_batch (
    batch_id text PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    return_url text,
    created_at timestamptz DEFAULT now() NOT NULL,
    expires_at timestamptz DEFAULT now() + '00:10:00' NOT NULL
);

CREATE TABLE pension_provider_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    pension_provider varchar(50) NOT NULL,
    pension_end_date date,
    pension_start_date date,
    CHECK (pension_start_date IS NULL OR pension_end_date IS NULL OR pension_start_date <= pension_end_date),
    EXCLUDE USING gist (tenant_id WITH =, daterange(COALESCE(pension_start_date, '-infinity'::date), COALESCE(pension_end_date, 'infinity'::date), '[]'::text) WITH &&)
);

CREATE TABLE persons (
    id int GENERATED BY DEFAULT AS IDENTITY,
    address_id int,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(75) NOT NULL,
    phone varchar(50) CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),
    email varchar(255) CHECK (email IS NULL OR email = lower(email)) CONSTRAINT persons_email_check1 CHECK (email IS NULL OR length(btrim(email)) > 0),
    national_identity_number varchar(11) CHECK (national_identity_number IS NULL OR national_identity_number ~ '^[0-9]{11}$'),
    efaktura_identifier varchar(9) CHECK (efaktura_identifier IS NULL OR efaktura_identifier ~ '^[0-9]{9}$'),
    date_of_birth date,
    gender varchar(32) CHECK (gender IN ('Man', 'Woman')),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (address_id, tenant_id) REFERENCES addresses ON DELETE SET NULL (address_id)
);

CREATE INDEX ON persons (address_id) WHERE address_id IS NOT NULL;

CREATE INDEX ON persons USING gin (name gin_trgm_ops);

CREATE INDEX ON persons (tenant_id);

CREATE TABLE customers (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    company_id int,
    person_id int,
    delivery_address_id int,
    days_until_due smallint CHECK (days_until_due IS NULL OR days_until_due >= 0 AND days_until_due <= 3000),
    default_discount_percent smallint CHECK (default_discount_percent IS NULL OR default_discount_percent >= 1 AND default_discount_percent <= 100),
    archived bool DEFAULT false NOT NULL,
    invoice_in_english bool DEFAULT false NOT NULL,
    automatic_reminder_enabled bool DEFAULT false NOT NULL,
    automatic_debt_collection_enabled bool DEFAULT true NOT NULL,
    invoice_email varchar(255) CHECK (invoice_email IS NULL OR invoice_email = lower(invoice_email)),
    iban varchar(34) CHECK (iban IS NULL OR length(btrim(iban)) > 0),
    bank_account_number varchar(50) CHECK (bank_account_number IS NULL OR length(btrim(bank_account_number)) > 0),
    swift_code varchar(11) CHECK (swift_code IS NULL OR length(btrim(swift_code)) > 0),
    number varchar(50) CHECK (number IS NULL OR btrim(number) <> ''),
    marketing_status text DEFAULT 'unknown' NOT NULL,
    marketing_email text,
    marketing_evidence text,
    marketing_updated_at timestamptz,
    sms_marketing_consent bool DEFAULT false NOT NULL,
    sms_marketing_phone text,
    sms_marketing_updated_at timestamptz,
    PRIMARY KEY (id, tenant_id),
    CHECK (company_id IS NULL <> (person_id IS NULL)),
    FOREIGN KEY (company_id, tenant_id) REFERENCES companies,
    FOREIGN KEY (delivery_address_id, tenant_id) REFERENCES addresses,
    FOREIGN KEY (person_id, tenant_id) REFERENCES persons ON DELETE CASCADE
);

CREATE INDEX ON customers (company_id);

CREATE INDEX ON customers (delivery_address_id) WHERE delivery_address_id IS NOT NULL;

CREATE INDEX ON customers USING gin (number gin_trgm_ops) WHERE number IS NOT NULL;

CREATE INDEX ON customers (person_id);

CREATE INDEX ON customers (tenant_id, archived, created_at DESC, id DESC);

CREATE UNIQUE INDEX ON customers (tenant_id, id);

CREATE UNIQUE INDEX ON customers (tenant_id, number) WHERE number IS NOT NULL;

CREATE TABLE customer_contact_methods (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    customer_id int NOT NULL,
    type text NOT NULL CHECK (type IN ('EMAIL', 'PHONE')),
    value text NOT NULL CHECK (value <> '' AND value = btrim(value)),
    is_primary bool DEFAULT false NOT NULL,
    CHECK (type = 'EMAIL' AND value = lower(value) OR type = 'PHONE' AND value ~ '^\+[0-9]+$'),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE CASCADE,
    UNIQUE (tenant_id, customer_id, type, value)
);

CREATE UNIQUE INDEX ON customer_contact_methods (tenant_id, customer_id, type) WHERE is_primary;

CREATE TABLE customer_contact_persons (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    customer_id int NOT NULL,
    name varchar(75) NOT NULL,
    email text,
    phone text,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE CASCADE
);

CREATE INDEX ON customer_contact_persons (tenant_id, customer_id, id);

CREATE TABLE customer_tags (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    customer_id int NOT NULL,
    name varchar(255) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    source varchar(300) NOT NULL,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON customer_tags (tenant_id, customer_id, source, lower(name));

CREATE INDEX ON customer_tags (tenant_id, lower(name));

CREATE TABLE employees (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    person_id int NOT NULL,
    national_identity_number varchar(20),
    department_id int,
    previous_system_ytd_salary numeric(15,2),
    previous_system_holiday_pay_basis numeric(15,2),
    previous_system_ytd_holiday_pay_basis numeric(15,2),
    holiday_allowance_scheme varchar(32),
    main_employer bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (department_id, tenant_id) REFERENCES departments,
    FOREIGN KEY (person_id, tenant_id) REFERENCES persons ON DELETE CASCADE
);

CREATE INDEX ON employees (department_id);

CREATE UNIQUE INDEX ON employees (tenant_id, id);

CREATE TABLE employee_bank_accounts (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    employee_id int PRIMARY KEY,
    country_code char(2) NOT NULL,
    bank_code varchar(20) DEFAULT '' NOT NULL,
    account_number varchar(30) DEFAULT '' NOT NULL,
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE,
    UNIQUE (tenant_id, country_code, bank_code, account_number, currency)
);

CREATE TABLE employee_recurring_post_tax_deductions (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    description varchar(255) NOT NULL CHECK (NULLIF(btrim(description), '') IS NOT NULL),
    monthly_amount numeric(15,2) NOT NULL CHECK (monthly_amount > 0),
    counter_account_number varchar(4) NOT NULL CHECK (counter_account_number ~ '^[0-9]{4}$'),
    legal_basis varchar(50) NOT NULL CHECK (legal_basis = 'WRITTEN_AGREEMENT'),
    agreement_reference varchar(500) NOT NULL CHECK (NULLIF(btrim(agreement_reference), '') IS NOT NULL),
    active bool DEFAULT true NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE
);

CREATE INDEX ON employee_recurring_post_tax_deductions (tenant_id, employee_id, active);

CREATE TABLE employment_relations (
    id int GENERATED BY DEFAULT AS IDENTITY,
    employee_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    start_date date NOT NULL,
    end_date date,
    enterprise_organization_number varchar(36),
    reporting_employment_id varchar(100),
    reported_leaves jsonb DEFAULT '[]' NOT NULL CHECK (jsonb_typeof(reported_leaves) = 'array'),
    end_reason varchar(32),
    PRIMARY KEY (id, tenant_id),
    CHECK (end_date IS NULL OR end_date >= start_date),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees
);

CREATE INDEX ON employment_relations (employee_id);

CREATE INDEX ON employment_relations (tenant_id);

CREATE TABLE employment_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    from_date date NOT NULL,
    municipality varchar(100),
    employment_type varchar(100),
    remuneration_type varchar(100),
    occupation_code varchar(7),
    percentage numeric(5,2),
    annual_salary numeric(15,2),
    hourly_wage numeric(15,2),
    employment_relation_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    form_of_employment varchar(50),
    working_hours_scheme varchar(50),
    last_salary_change_date date,
    last_position_percentage_change_date date,
    full_time_hours_per_week numeric(5,2) DEFAULT 37.50 NOT NULL CHECK (full_time_hours_per_week > 0 AND full_time_hours_per_week <= 168),
    FOREIGN KEY (employment_relation_id, tenant_id) REFERENCES employment_relations
);

CREATE INDEX ON employment_lines (employment_relation_id);

CREATE TABLE gift_cards (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    code varchar(32) NOT NULL,
    currency char(3) NOT NULL,
    initial_amount numeric(15,2) NOT NULL CHECK (initial_amount > 0),
    balance numeric(15,2) NOT NULL CHECK (balance >= 0),
    issued_at timestamptz DEFAULT now() NOT NULL,
    issued_reference varchar(120),
    voided_at timestamptz,
    customer_id int,
    recipient_email varchar(254),
    email_outbox_id int,
    comment varchar(2000),
    CHECK (balance <= initial_amount),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (email_outbox_id, tenant_id) REFERENCES email_outbox(id, sender_tenant_id),
    UNIQUE (id, tenant_id)
);

COMMENT ON TABLE gift_cards IS 'Prepaid balances the tenant issues itself. The balance is a liability until the card pays for goods.';

CREATE UNIQUE INDEX ON gift_cards (tenant_id, code);

CREATE INDEX ON gift_cards (tenant_id, customer_id) WHERE customer_id IS NOT NULL;

CREATE UNIQUE INDEX ON gift_cards (tenant_id, issued_reference) WHERE issued_reference IS NOT NULL;

CREATE TABLE gift_card_transactions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    gift_card_id int NOT NULL,
    occurred_at timestamptz DEFAULT now() NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount <> 0),
    balance_after numeric(15,2) NOT NULL CHECK (balance_after >= 0),
    reference varchar(120) NOT NULL,
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, gift_card_id, reference)
);

COMMENT ON TABLE gift_card_transactions IS 'Every balance change with the sale or order reference that caused it; one reference changes a card once.';

CREATE TABLE hour_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    date date NOT NULL,
    allocated_hours numeric(10,2) DEFAULT 0.00 NOT NULL,
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE RESTRICT
);

CREATE INDEX ON hour_allocations (employee_id);

CREATE TABLE posting_groups (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    description text,
    origin varchar(40) NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

CREATE INDEX ON posting_groups (tenant_id);

CREATE TABLE product_attribute_values (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL UNIQUE,
    attribute_key varchar(100) NOT NULL CHECK (attribute_key <> '' AND attribute_key = btrim(attribute_key)),
    source varchar(300) NOT NULL CHECK (source <> '' AND source = btrim(source)),
    external_id varchar(255) NOT NULL CHECK (external_id <> '' AND external_id = btrim(external_id)),
    label varchar(255) NOT NULL CHECK (label <> '' AND label = btrim(label)),
    taxonomy_value_ids text[] DEFAULT '{}' NOT NULL,
    colors text[] DEFAULT '{}' NOT NULL,
    source_fields jsonb DEFAULT '{}' NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, source, attribute_key, external_id)
);

CREATE TABLE product_price_lists (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    public_id uuid DEFAULT uuidv7() NOT NULL UNIQUE,
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE product_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    show_images bool DEFAULT true NOT NULL,
    show_variants bool DEFAULT true NOT NULL,
    show_cost_and_margin bool DEFAULT true NOT NULL,
    show_suppliers bool DEFAULT true NOT NULL,
    show_brand_and_barcode bool DEFAULT true NOT NULL,
    show_shipping_and_customs bool DEFAULT true NOT NULL,
    show_category_and_material bool,
    show_product_code bool DEFAULT false NOT NULL,
    bundles_enabled bool DEFAULT false NOT NULL,
    gift_cards_enabled bool DEFAULT false NOT NULL
);

COMMENT ON COLUMN product_settings.gift_cards_enabled IS 'Whether this tenant sells gift cards at all; off hides them from products, the till and the menu.';

CREATE TABLE products (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    is_stock_item bool DEFAULT false NOT NULL,
    is_archived bool DEFAULT false NOT NULL,
    title varchar(255) NOT NULL,
    description text,
    vat_code varchar(10),
    revenue_account_code varchar(10) CHECK (revenue_account_code IS NULL OR revenue_account_code ~ '^3[0-9]{3}$'),
    seo_title varchar(70),
    seo_description varchar(320),
    brand varchar(255) CHECK (brand IS NULL OR brand = btrim(brand) AND brand <> ''),
    taxonomy_category_id varchar(64),
    material varchar(255),
    product_code varchar(255),
    product_code_source varchar(255),
    product_type varchar(255),
    pos_available bool DEFAULT true NOT NULL,
    locally_managed_attributes text[] DEFAULT '{}' NOT NULL,
    is_gift_card bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

COMMENT ON COLUMN products.is_gift_card IS 'Selling this product issues a gift card for the line amount instead of recording revenue.';

CREATE INDEX ON products USING gin (brand gin_trgm_ops) WHERE brand IS NOT NULL;

CREATE INDEX ON products (tenant_id, is_archived, title, id);

CREATE INDEX ON products (tenant_id, lower(TRIM(BOTH FROM title)));

CREATE INDEX ON products (title);

CREATE INDEX products_title_idx1 ON products USING gin (title gin_trgm_ops);

ALTER TABLE products CLUSTER ON products_tenant_id_is_archived_title_id_idx;

CREATE CONSTRAINT TRIGGER archived_products_cannot_remain_published AFTER UPDATE OF is_archived ON products DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_site_publications_after_product_archive();

CREATE CONSTRAINT TRIGGER products_require_variant AFTER INSERT OR UPDATE ON products DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_product_after_change();

CREATE TABLE product_images (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    "position" smallint NOT NULL CHECK ("position" >= 0),
    alt_text varchar(500),
    content_type varchar(50) NOT NULL,
    file_data bytea STORAGE EXTERNAL NOT NULL,
    byte_size int NOT NULL,
    width int NOT NULL,
    height int NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    source_fingerprint text,
    public_id uuid DEFAULT uuidv7() NOT NULL UNIQUE,
    filename varchar(255) NOT NULL CHECK (filename ~ '^[a-z0-9]+(-[a-z0-9]+)*\.avif$'),
    CHECK (byte_size > 0 AND byte_size <= 10485760 AND byte_size = octet_length(file_data)),
    CONSTRAINT product_images_check1 CHECK (width > 0 AND height > 0),
    UNIQUE (id, product_id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    UNIQUE (tenant_id, product_id, "position") DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ON product_images (tenant_id, product_id, "position", id);

CREATE UNIQUE INDEX ON product_images (tenant_id, product_id, source_fingerprint) WHERE source_fingerprint IS NOT NULL;

ALTER TABLE product_images CLUSTER ON product_images_tenant_id_product_id_position_id_idx;

CREATE TABLE product_image_translations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    product_image_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    alt_text varchar(500) NOT NULL CHECK (length(btrim(alt_text)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (product_image_id, product_id, tenant_id) REFERENCES product_images(id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (product_image_id, tenant_id, locale)
);

CREATE INDEX ON product_image_translations (tenant_id, product_id, locale);

CREATE TABLE product_options (
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    "position" smallint NOT NULL CHECK ("position" >= 0),
    name varchar(255) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    normalized_name text GENERATED ALWAYS AS (lower(name)) STORED,
    UNIQUE (id, product_id, tenant_id),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    UNIQUE (tenant_id, product_id, normalized_name) DEFERRABLE INITIALLY DEFERRED,
    UNIQUE (tenant_id, product_id, "position") DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE product_option_translations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    product_option_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    name varchar(255) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (product_option_id, product_id, tenant_id) REFERENCES product_options(id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (product_option_id, tenant_id, locale)
);

CREATE INDEX ON product_option_translations (tenant_id, product_id, locale);

CREATE TABLE product_option_values (
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    product_option_id int NOT NULL,
    "position" smallint NOT NULL CHECK ("position" >= 0),
    value varchar(255) NOT NULL CHECK (value = btrim(value) AND value <> ''),
    normalized_value text GENERATED ALWAYS AS (lower(value)) STORED,
    UNIQUE (id, product_option_id, product_id, tenant_id),
    FOREIGN KEY (product_option_id, product_id, tenant_id) REFERENCES product_options(id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, product_option_id, normalized_value) DEFERRABLE INITIALLY DEFERRED,
    UNIQUE (tenant_id, product_option_id, "position") DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE product_attribute_assignments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    value_id int NOT NULL,
    product_option_id int,
    product_option_value_id int,
    CHECK (product_option_id IS NULL = (product_option_value_id IS NULL)),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    FOREIGN KEY (product_option_value_id, product_option_id, product_id, tenant_id) REFERENCES product_option_values(id, product_option_id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE NULLS NOT DISTINCT (tenant_id, product_id, value_id, product_option_value_id),
    FOREIGN KEY (value_id, tenant_id) REFERENCES product_attribute_values(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON product_attribute_assignments (tenant_id, product_id);

CREATE INDEX ON product_attribute_assignments (tenant_id, value_id);

CREATE TABLE product_option_value_translations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    product_option_id int NOT NULL,
    product_option_value_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    value varchar(255) NOT NULL CHECK (length(btrim(value)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (product_option_value_id, product_option_id, product_id, tenant_id) REFERENCES product_option_values(id, product_option_id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (product_option_value_id, tenant_id, locale)
);

CREATE INDEX ON product_option_value_translations (tenant_id, product_id, locale);

CREATE TABLE product_tags (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    name varchar(255) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    source varchar(300) NOT NULL,
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE
);

CREATE INDEX ON product_tags (tenant_id, lower(name));

CREATE UNIQUE INDEX ON product_tags (tenant_id, product_id, source, lower(name));

CREATE TABLE product_translations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    product_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    title varchar(255),
    description text,
    seo_title varchar(70),
    seo_description varchar(320),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (title IS NOT NULL OR description IS NOT NULL OR seo_title IS NOT NULL OR seo_description IS NOT NULL),
    CONSTRAINT product_translations_check1 CHECK ((title IS NULL OR length(btrim(title)) > 0) AND (description IS NULL OR length(btrim(description)) > 0) AND (seo_title IS NULL OR length(btrim(seo_title)) > 0) AND (seo_description IS NULL OR length(btrim(seo_description)) > 0)),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    UNIQUE (product_id, tenant_id, locale)
);

CREATE INDEX ON product_translations (tenant_id, locale, product_id);

CREATE TABLE product_variants (
    id int GENERATED BY DEFAULT AS IDENTITY,
    product_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    row_number smallint DEFAULT 0 NOT NULL,
    public_id uuid DEFAULT uuidv7() NOT NULL,
    replenishable bool DEFAULT true NOT NULL,
    sku varchar(100) NOT NULL,
    barcode varchar(100),
    cost_price numeric(15,2) DEFAULT 0 NOT NULL,
    selling_price numeric(15,2) DEFAULT 0 NOT NULL,
    margin_percent numeric(5,2) GENERATED ALWAYS AS (
CASE
    WHEN selling_price > 0 THEN GREATEST(LEAST(round((selling_price - cost_price) / selling_price * 100, 2), 99.99), '-999.99')
    ELSE NULL
END) STORED,
    hs_code varchar(13) CHECK (hs_code IS NULL OR hs_code ~ '^[0-9]{6,13}$'),
    country_of_origin_code char(2) CHECK (country_of_origin_code IS NULL OR country_of_origin_code ~ '^[A-Z]{2}$'),
    weight_grams numeric(15,3) CHECK (weight_grams IS NULL OR weight_grams >= 0),
    option_value_signature int[],
    compare_at_price numeric(15,2),
    allow_overselling bool DEFAULT false NOT NULL,
    bundle_definition_locked bool DEFAULT false NOT NULL,
    pos_popularity_score numeric(15,4) DEFAULT 0 NOT NULL,
    PRIMARY KEY (id, tenant_id),
    CHECK (compare_at_price IS NULL OR compare_at_price > 0 AND compare_at_price > selling_price),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    UNIQUE (tenant_id, sku)
);

CREATE INDEX ON product_variants USING gin (barcode gin_trgm_ops) WHERE barcode IS NOT NULL;

CREATE UNIQUE INDEX ON product_variants (id, product_id, tenant_id);

CREATE INDEX ON product_variants (product_id);

CREATE UNIQUE INDEX ON product_variants (public_id);

CREATE INDEX ON product_variants USING gin (sku gin_trgm_ops);

CREATE INDEX ON product_variants (tenant_id, pos_popularity_score DESC, id);

CREATE UNIQUE INDEX ON product_variants (tenant_id, product_id, option_value_signature) WHERE option_value_signature IS NOT NULL;

ALTER TABLE product_variants CLUSTER ON product_variants_product_id_idx;

CREATE CONSTRAINT TRIGGER product_variants_preserve_product_variant AFTER DELETE OR UPDATE OF product_id, tenant_id ON product_variants DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_product_after_variant_removal();

CREATE TABLE product_bundle_components (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    bundle_variant_id int NOT NULL,
    component_variant_id int NOT NULL,
    quantity int NOT NULL CHECK (quantity > 0),
    UNIQUE (bundle_variant_id, component_variant_id, tenant_id),
    FOREIGN KEY (bundle_variant_id, tenant_id) REFERENCES product_variants,
    CHECK (bundle_variant_id <> component_variant_id),
    FOREIGN KEY (component_variant_id, tenant_id) REFERENCES product_variants
);

CREATE INDEX ON product_bundle_components (component_variant_id, tenant_id);

CREATE TABLE product_variant_prices (
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    price_list_id int NOT NULL,
    product_variant_id int NOT NULL,
    selling_price numeric(15,2) NOT NULL CHECK (selling_price >= 0),
    compare_at_price numeric(15,2),
    CHECK (compare_at_price IS NULL OR compare_at_price > selling_price),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (price_list_id, tenant_id) REFERENCES product_price_lists(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (price_list_id, tenant_id, product_variant_id),
    FOREIGN KEY (product_variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE
);

CREATE INDEX ON product_variant_prices (product_variant_id, tenant_id);

CREATE TABLE project_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    default_hourly_rate numeric(10,2) CHECK (default_hourly_rate IS NULL OR default_hourly_rate > 0 AND default_hourly_rate <= 10000000)
);

CREATE TABLE projects (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(150) NOT NULL,
    is_archived bool DEFAULT false NOT NULL,
    responsible_employee_id int,
    parent_id int,
    invoice_customer_id int,
    hourly_rate numeric(10,2) CHECK (hourly_rate IS NULL OR hourly_rate > 0 AND hourly_rate <= 10000000),
    is_billable bool DEFAULT true NOT NULL,
    external_source varchar(40),
    external_id varchar(100),
    customer_id int,
    PRIMARY KEY (id, tenant_id),
    CHECK (external_source IS NULL = (external_id IS NULL)),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (customer_id),
    FOREIGN KEY (invoice_customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (invoice_customer_id),
    FOREIGN KEY (parent_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (responsible_employee_id, tenant_id) REFERENCES employees
);

CREATE INDEX ON projects (invoice_customer_id);

CREATE INDEX ON projects (parent_id);

CREATE INDEX ON projects (tenant_id, customer_id);

CREATE UNIQUE INDEX ON projects (tenant_id, external_source, external_id);

CREATE TABLE project_activities (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id int NOT NULL,
    activity_id int NOT NULL REFERENCES activities,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    UNIQUE (project_id, activity_id),
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects ON DELETE CASCADE
);

CREATE INDEX ON project_activities (tenant_id);

CREATE TABLE project_timers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    project_id int,
    activity_id int REFERENCES activities,
    request_id uuid NOT NULL,
    started_at timestamptz NOT NULL,
    stopped_at timestamptz,
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects ON DELETE RESTRICT,
    UNIQUE (tenant_id, employee_id, request_id)
);

CREATE INDEX ON project_timers (project_id);

CREATE INDEX ON project_timers (started_at, id) WHERE stopped_at IS NULL;

CREATE UNIQUE INDEX ON project_timers (tenant_id, employee_id) WHERE stopped_at IS NULL;

CREATE TABLE reconciliation_groups (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    company_bank_id int NOT NULL,
    created_by int,
    match_source varchar(40) CHECK (match_source IS NULL OR match_source IN ('MANUAL_POSTING_LINK', 'RECONCILIATION_GENERATED_VOUCHER', 'RECONCILIATION_RULE_VOUCHER', 'VAT_REFUND_AUTO_MATCH')),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank ON DELETE CASCADE
);

CREATE INDEX ON reconciliation_groups (company_bank_id, tenant_id);

CREATE INDEX ON reconciliation_groups (tenant_id);

CREATE TABLE report_email_schedules (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_type varchar(64) NOT NULL,
    frequency varchar(16) NOT NULL,
    recipients text NOT NULL CHECK (btrim(recipients) <> ''),
    delay_days int DEFAULT 0 NOT NULL CHECK (delay_days >= 0 AND delay_days <= 31),
    enabled bool DEFAULT true NOT NULL
);

CREATE INDEX ON report_email_schedules (enabled, tenant_id);

CREATE TABLE report_email_deliveries (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    schedule_id int NOT NULL REFERENCES report_email_schedules ON DELETE CASCADE,
    period_start date NOT NULL,
    period_end date NOT NULL,
    sent_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (schedule_id, period_start, period_end)
);

CREATE TABLE rrh_beneficial_owner (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    organization_number varchar(9) NOT NULL,
    national_id_number_plaintext varchar(11),
    first_name varchar(200),
    last_name varchar(200),
    birth_year smallint,
    updated_at timestamptz DEFAULT now() NOT NULL
);

CREATE TABLE salary_payout_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    payout_day_of_month smallint NOT NULL CHECK (payout_day_of_month >= 1 AND payout_day_of_month <= 31)
);

CREATE TABLE salary_run_reminders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    period_year smallint NOT NULL,
    period_month smallint NOT NULL CHECK (period_month >= 1 AND period_month <= 12),
    last_sent_date date NOT NULL,
    UNIQUE (tenant_id, period_year, period_month)
);

CREATE TABLE shareholder_company_information (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 1900),
    number_of_shares_january numeric(18,2) DEFAULT 0 NOT NULL,
    number_of_shares_december numeric(18,2) DEFAULT 0 NOT NULL,
    nominal_value_january numeric(22,6) DEFAULT 0 NOT NULL,
    nominal_value_december numeric(22,6) DEFAULT 0 NOT NULL,
    share_capital_january numeric(18,2) DEFAULT 0 NOT NULL,
    share_capital_december numeric(18,2) DEFAULT 0 NOT NULL,
    paid_up_share_capital_january numeric(18,2) DEFAULT 0 NOT NULL,
    paid_up_share_capital_december numeric(18,2) DEFAULT 0 NOT NULL,
    paid_up_premium_january numeric(18,2) DEFAULT 0 NOT NULL,
    paid_up_premium_december numeric(18,2) DEFAULT 0 NOT NULL,
    share_class_name varchar(128) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    share_class_code varchar(2) DEFAULT '01' NOT NULL,
    UNIQUE (tenant_id, report_year, share_class_code)
);

CREATE TABLE shareholder_identities (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    registration_number varchar(36) DEFAULT '' NOT NULL,
    name varchar(255) NOT NULL,
    email varchar(255) CHECK (email IS NULL OR email = lower(email)) CONSTRAINT shareholder_identities_email_check1 CHECK (email IS NULL OR length(btrim(email)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

CREATE UNIQUE INDEX ON shareholder_identities (tenant_id, lower(btrim(name))) WHERE registration_number = '';

CREATE UNIQUE INDEX ON shareholder_identities (tenant_id, registration_number) WHERE registration_number <> '';

CREATE TABLE shareholder_register_auto_submission_attempts (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL,
    attempted_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, report_year)
);

CREATE TABLE shareholder_register_auto_submission_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    enabled bool DEFAULT false NOT NULL
);

CREATE TABLE shareholder_register_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 1900),
    submission_id varchar(64) NOT NULL,
    confirmation_reference varchar(64),
    main_form_id varchar(64),
    delivery_reference varchar(128),
    dialog_id varchar(128),
    dispatch_id varchar(128),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    approval_state varchar(20),
    status_checked_at timestamptz,
    pdf_available bool,
    source varchar(20) DEFAULT 'LOCAL' NOT NULL,
    UNIQUE (tenant_id, report_year, submission_id)
);

CREATE TABLE shareholder_splits (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year int NOT NULL,
    share_class_code varchar(8) NOT NULL,
    registered_on date NOT NULL,
    registered_at time(0) without time zone NOT NULL,
    new_shares int NOT NULL,
    old_shares int NOT NULL CHECK (old_shares > 0),
    evidence_reference varchar(1024) NOT NULL,
    CHECK (new_shares > old_shares),
    CONSTRAINT shareholder_splits_check1 CHECK (EXTRACT(year FROM registered_on) = report_year),
    UNIQUE (tenant_id, report_year, share_class_code, registered_on, registered_at)
);

CREATE TABLE shareholders (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 1900),
    previous_year_quantity int DEFAULT 0 NOT NULL CHECK (previous_year_quantity >= 0),
    current_year_quantity int DEFAULT 0 NOT NULL CHECK (current_year_quantity >= 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    share_class_code varchar(2) DEFAULT '01' NOT NULL,
    identity_id int NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (identity_id, tenant_id) REFERENCES shareholder_identities
);

CREATE INDEX ON shareholders (tenant_id, identity_id);

CREATE UNIQUE INDEX ON shareholders (tenant_id, report_year, share_class_code, identity_id);

CREATE TABLE shareholder_demerger_cancellations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 2025),
    shareholder_id int NOT NULL,
    share_class_code varchar(8) NOT NULL,
    registered_on date NOT NULL,
    registered_at time NOT NULL,
    recipient_organization_number varchar(9) NOT NULL,
    recipient_share_class_code varchar(8) NOT NULL,
    share_quantity int NOT NULL CHECK (share_quantity > 0),
    nominal_value numeric(18,6) NOT NULL CHECK (nominal_value > 0),
    consideration_nominal_value numeric(18,6) NOT NULL CHECK (consideration_nominal_value > 0),
    consideration_shares int NOT NULL CHECK (consideration_shares > 0),
    tax_paid_capital_transferred numeric(18,2) CHECK (tax_paid_capital_transferred >= 0),
    tax_paid_premium_transferred numeric(18,2) CHECK (tax_paid_premium_transferred >= 0),
    evidence_reference varchar(1024) NOT NULL CHECK (btrim(evidence_reference) <> ''),
    UNIQUE (tenant_id, report_year, share_class_code, registered_on, registered_at, shareholder_id),
    FOREIGN KEY (shareholder_id, tenant_id) REFERENCES shareholders,
    CHECK (EXTRACT(year FROM registered_on) = report_year)
);

CREATE TABLE shareholder_demerger_nominal_increases (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 2025),
    shareholder_id int NOT NULL,
    share_class_code varchar(8) NOT NULL,
    registered_on date NOT NULL,
    registered_at time NOT NULL,
    transferor_organization_number varchar(9) NOT NULL,
    transferor_share_class_code varchar(8) NOT NULL,
    share_quantity int NOT NULL CHECK (share_quantity > 0),
    nominal_increase_per_share numeric(18,6) NOT NULL CHECK (nominal_increase_per_share > 0),
    nominal_value_after numeric(18,6) NOT NULL,
    premium_increase numeric(18,2) NOT NULL CHECK (premium_increase >= 0),
    tax_paid_capital_transferred numeric(18,2) CHECK (tax_paid_capital_transferred >= 0),
    tax_paid_premium_transferred numeric(18,2) CHECK (tax_paid_premium_transferred >= 0),
    evidence_reference varchar(1024) NOT NULL CHECK (btrim(evidence_reference) <> ''),
    UNIQUE (tenant_id, report_year, share_class_code, registered_on, registered_at, shareholder_id),
    FOREIGN KEY (shareholder_id, tenant_id) REFERENCES shareholders,
    CHECK (nominal_value_after >= nominal_increase_per_share),
    CONSTRAINT shareholder_demerger_nominal_increases_check1 CHECK (EXTRACT(year FROM registered_on) = report_year)
);

CREATE TABLE shareholder_transactions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 1900),
    shareholder_id int,
    transaction_type varchar(64) NOT NULL,
    occurred_on date NOT NULL,
    occurred_at time,
    share_quantity int DEFAULT 0 NOT NULL CHECK (share_quantity >= 0),
    compensation_amount numeric(18,2) DEFAULT 0 NOT NULL CHECK (compensation_amount >= 0),
    notes varchar(1024),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    share_class_code varchar(2) DEFAULT '01' NOT NULL,
    counterparty_shareholder_id int,
    acquisition_value numeric(18,2),
    tax_treatment_confirmed bool DEFAULT false NOT NULL,
    FOREIGN KEY (counterparty_shareholder_id, tenant_id) REFERENCES shareholders ON DELETE SET NULL (counterparty_shareholder_id),
    FOREIGN KEY (shareholder_id, tenant_id) REFERENCES shareholders ON DELETE SET NULL (shareholder_id)
);

CREATE INDEX ON shareholder_transactions (counterparty_shareholder_id);

CREATE INDEX ON shareholder_transactions (shareholder_id);

CREATE INDEX ON shareholder_transactions (tenant_id, report_year, share_class_code);

CREATE TABLE shipment_pickup_settings (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    carrier_code varchar(20) NOT NULL,
    instructions varchar(80) DEFAULT '' NOT NULL,
    contact_email varchar(254),
    automatic_pickup_enabled bool DEFAULT false NOT NULL,
    PRIMARY KEY (tenant_id, carrier_code)
);

CREATE TABLE shipment_pickups (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    carrier_code varchar(32) NOT NULL,
    proposal jsonb NOT NULL,
    request jsonb NOT NULL,
    status varchar(32) NOT NULL,
    provider_reference varchar(200),
    created_at timestamptz DEFAULT now() NOT NULL,
    cancellation_started_at timestamptz,
    provider_pickup_id varchar(200),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, request_id)
);

CREATE INDEX ON shipment_pickups (tenant_id, carrier_code, id DESC);

CREATE TABLE shipment_return_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    domestic_service varchar(100),
    international_service varchar(100)
);

CREATE TABLE sites (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    public_id uuid NOT NULL UNIQUE,
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    locale varchar(35) NOT NULL,
    active_domain varchar(253),
    status varchar(20) NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    preview_domain varchar(253),
    slug varchar(100) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    UNIQUE (id, tenant_id),
    UNIQUE (public_id, tenant_id),
    UNIQUE (tenant_id, slug)
);

CREATE TABLE email_marketing_flow (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('welcome', 'welcome_discount', 'back_in_stock', 'abandoned_checkout', 'post_purchase', 'winback')),
    enabled bool DEFAULT false NOT NULL,
    activated_at timestamptz,
    subject text,
    headline text,
    body text,
    button_label text,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL UNIQUE,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id),
    UNIQUE (tenant_id, site_id, kind)
);

CREATE TABLE email_marketing_flow_event (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    flow_id int NOT NULL,
    kind text NOT NULL CHECK (kind IN ('welcome', 'welcome_discount', 'back_in_stock', 'abandoned_checkout', 'post_purchase', 'winback')),
    email varchar(254) NOT NULL,
    customer_id int,
    name text DEFAULT '' NOT NULL,
    locale varchar(35) NOT NULL,
    reference_key text NOT NULL,
    title text DEFAULT '' NOT NULL,
    action_url text NOT NULL,
    image_url text,
    state text DEFAULT 'waiting' NOT NULL CHECK (state IN ('waiting', 'queued', 'cancelled')),
    created_at timestamptz DEFAULT now() NOT NULL,
    next_check_at timestamptz DEFAULT now() NOT NULL,
    expires_at timestamptz NOT NULL,
    stage int DEFAULT 0 NOT NULL CHECK (stage >= 0 AND stage <= 10),
    enrolled_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (customer_id),
    FOREIGN KEY (flow_id, tenant_id) REFERENCES email_marketing_flow(id, tenant_id),
    UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ON email_marketing_flow_event (flow_id, email, reference_key) WHERE kind = 'back_in_stock' AND state IN ('waiting', 'queued');

CREATE UNIQUE INDEX ON email_marketing_flow_event (flow_id, email, reference_key, stage) WHERE kind <> 'back_in_stock';

CREATE INDEX ON email_marketing_flow_event (next_check_at, id) WHERE state = 'waiting';

CREATE INDEX ON email_marketing_flow_event (tenant_id, flow_id, id);

CREATE TABLE email_marketing_flow_stage (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    flow_id int NOT NULL,
    stage int NOT NULL CHECK (stage >= 1 AND stage <= 10),
    delay_hours int NOT NULL CHECK (delay_hours >= 1 AND delay_hours <= 17520),
    subject varchar(200) NOT NULL CHECK (subject !~ '[\r\n]'),
    headline varchar(200) NOT NULL,
    body text NOT NULL CHECK (length(body) <= 5000),
    button_label varchar(75) NOT NULL,
    action_url varchar(1000),
    PRIMARY KEY (flow_id, stage),
    FOREIGN KEY (flow_id, tenant_id) REFERENCES email_marketing_flow(id, tenant_id)
);

CREATE TABLE email_marketing_recipient (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    campaign_id int,
    customer_id int,
    email text NOT NULL,
    name text NOT NULL,
    subject text NOT NULL,
    body text,
    sender_address text NOT NULL,
    sender_name text,
    is_test bool DEFAULT false NOT NULL,
    unsubscribe_token uuid NOT NULL UNIQUE,
    status text DEFAULT 'pending' NOT NULL CHECK (status IN ('pending', 'queued', 'delivered', 'failed', 'skipped', 'cancelled', 'unknown')),
    bud_id int,
    attempts int DEFAULT 0 NOT NULL,
    next_attempt_at timestamptz DEFAULT now() NOT NULL,
    lease_until timestamptz,
    lease_token uuid,
    created_at timestamptz DEFAULT now() NOT NULL,
    expires_at timestamptz NOT NULL,
    error text,
    body_format text DEFAULT 'text' NOT NULL CHECK (body_format IN ('text', 'html')),
    flow_event_id int,
    clicked_at timestamptz,
    flow_action_url text,
    FOREIGN KEY (campaign_id, tenant_id) REFERENCES email_marketing_campaign(id, tenant_id),
    CHECK (num_nonnulls(campaign_id, flow_event_id) = 1),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (customer_id),
    FOREIGN KEY (flow_event_id, tenant_id) REFERENCES email_marketing_flow_event(id, tenant_id)
);

CREATE UNIQUE INDEX ON email_marketing_recipient (campaign_id, email) WHERE NOT is_test;

CREATE UNIQUE INDEX ON email_marketing_recipient (flow_event_id) WHERE flow_event_id IS NOT NULL;

CREATE INDEX ON email_marketing_recipient (next_attempt_at, id) WHERE status IN ('pending', 'queued');

CREATE INDEX ON email_marketing_recipient (tenant_id, campaign_id, id);

CREATE INDEX ON email_marketing_recipient (tenant_id, lower(TRIM(BOTH FROM email)), campaign_id) WHERE NOT is_test AND status = 'delivered';

CREATE TABLE google_merchant_sources (
    site_id uuid NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    market varchar(100) NOT NULL,
    locale varchar(35) NOT NULL,
    account_id varchar(20) NOT NULL,
    source_name varchar(100) PRIMARY KEY,
    feed_url text NOT NULL,
    registered_at timestamptz DEFAULT now() NOT NULL,
    account_name text,
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON google_merchant_sources (tenant_id, site_id);

CREATE TABLE google_shopping_feed (
    site_id uuid PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    profiles jsonb DEFAULT '[]' NOT NULL CHECK (jsonb_typeof(profiles) = 'array'),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE meta_catalog_feed (
    site_id uuid PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    profiles jsonb DEFAULT '[]' NOT NULL CHECK (jsonb_typeof(profiles) = 'array'),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE meta_commerce_localizations (
    site_id uuid NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    market varchar(100) NOT NULL,
    locale varchar(35) NOT NULL,
    business_id varchar(30) NOT NULL,
    catalog_id varchar(30) NOT NULL,
    feed_id varchar(30) NOT NULL UNIQUE,
    feed_url text NOT NULL,
    registered_at timestamptz DEFAULT now() NOT NULL,
    catalog_name text,
    source_key text NOT NULL CHECK (source_key <> 'primary'),
    PRIMARY KEY (catalog_id, source_key),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON meta_commerce_localizations (tenant_id, site_id);

CREATE TABLE meta_commerce_sources (
    site_id uuid NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    market varchar(100) NOT NULL,
    locale varchar(35) NOT NULL,
    business_id varchar(30) NOT NULL,
    catalog_id varchar(30) PRIMARY KEY,
    feed_id varchar(30) NOT NULL UNIQUE,
    feed_url text NOT NULL,
    registered_at timestamptz DEFAULT now() NOT NULL,
    catalog_name text,
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON meta_commerce_sources (tenant_id, site_id);

CREATE TABLE site_commerce_event (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id int NOT NULL,
    event_id uuid NOT NULL,
    session_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('page_view', 'product_view', 'add_to_cart', 'remove_from_cart', 'checkout_view')),
    path varchar(500) NOT NULL CHECK (path ~~ '/%' AND path !~ '[?#[:space:]]'),
    product_id uuid,
    variant_id uuid,
    quantity int CHECK (quantity >= 1 AND quantity <= 100),
    received_at timestamptz DEFAULT now() NOT NULL,
    analytics_source text DEFAULT 'unknown' NOT NULL CHECK (analytics_source IN ('live', 'preview', 'unknown')),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id),
    UNIQUE (tenant_id, site_id, event_id)
);

CREATE INDEX ON site_commerce_event (received_at, id);

CREATE INDEX ON site_commerce_event (tenant_id, received_at, site_id);

CREATE INDEX ON site_commerce_event (tenant_id, site_id, received_at) WHERE analytics_source = 'live';

CREATE TABLE site_commerce_products (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id int NOT NULL,
    product_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    handle varchar(100) NOT NULL CHECK (handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    published bool NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    subscription_interval_months smallint CHECK (subscription_interval_months IS NULL OR subscription_interval_months >= 1 AND subscription_interval_months <= 12),
    UNIQUE (id, site_id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products,
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, tenant_id, handle),
    UNIQUE (site_id, tenant_id, product_id)
);

CREATE INDEX ON site_commerce_products (site_id, tenant_id, published);

CREATE CONSTRAINT TRIGGER site_publications_require_active_product AFTER INSERT OR UPDATE OF product_id, tenant_id, published ON site_commerce_products DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_site_publication_after_change();

CREATE TABLE site_commerce_product_variants (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    site_commerce_product_id int NOT NULL,
    product_variant_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    FOREIGN KEY (product_variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE,
    FOREIGN KEY (site_commerce_product_id, tenant_id) REFERENCES site_commerce_products(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, tenant_id, product_variant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON site_commerce_product_variants (site_commerce_product_id, tenant_id);

CREATE TABLE site_commerce_settings (
    site_id int PRIMARY KEY,
    tenant_id int NOT NULL,
    catalog_version int NOT NULL CHECK (catalog_version >= 1),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    fallback_unit_weight_grams int CHECK (fallback_unit_weight_grams > 0),
    support_email varchar(254),
    business_sales_enabled bool DEFAULT false NOT NULL,
    payment_page_tracking_since timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, tenant_id)
);

CREATE TABLE site_commerce_collections (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    handle varchar(100) NOT NULL CHECK (handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title varchar(255) NOT NULL CHECK (length(btrim(title)) > 0),
    description text,
    seo_title varchar(70) CHECK (seo_title IS NULL OR length(btrim(seo_title)) > 0),
    seo_description varchar(320) CHECK (seo_description IS NULL OR length(btrim(seo_description)) > 0),
    published bool NOT NULL,
    sort_order smallint NOT NULL CHECK (sort_order >= 0),
    type varchar(20) NOT NULL,
    rules jsonb,
    sort varchar(20) NOT NULL,
    shopify_collection_id bigint,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    locally_edited bool DEFAULT false NOT NULL,
    CHECK (type = 'AUTOMATED' OR rules IS NULL),
    UNIQUE (id, site_id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES site_commerce_settings(site_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT site_commerce_collections_site_id_tenant_id_fkey1 FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, tenant_id, handle)
);

CREATE INDEX ON site_commerce_collections (site_id, tenant_id, published, sort_order, id);

CREATE UNIQUE INDEX ON site_commerce_collections (site_id, tenant_id, shopify_collection_id) WHERE shopify_collection_id IS NOT NULL;

CREATE TABLE site_commerce_collection_images (
    collection_id int PRIMARY KEY,
    site_id int NOT NULL,
    tenant_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    source_fingerprint char(64) NOT NULL,
    file_data bytea STORAGE EXTERNAL NOT NULL,
    FOREIGN KEY (collection_id, site_id, tenant_id) REFERENCES site_commerce_collections(id, site_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE site_commerce_collection_products (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    collection_id int NOT NULL,
    site_commerce_product_id int NOT NULL,
    "position" int NOT NULL CHECK ("position" >= 0),
    FOREIGN KEY (collection_id, site_id, tenant_id) REFERENCES site_commerce_collections(id, site_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (collection_id, tenant_id, "position"),
    UNIQUE (collection_id, tenant_id, site_commerce_product_id),
    FOREIGN KEY (site_commerce_product_id, site_id, tenant_id) REFERENCES site_commerce_products(id, site_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON site_commerce_collection_products (site_commerce_product_id, tenant_id);

CREATE TABLE site_commerce_collection_translations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    collection_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    title varchar(255),
    description text,
    seo_title varchar(70),
    seo_description varchar(320),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (collection_id, site_id, tenant_id) REFERENCES site_commerce_collections(id, site_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (collection_id, tenant_id, locale),
    CHECK (title IS NOT NULL OR description IS NOT NULL OR seo_title IS NOT NULL OR seo_description IS NOT NULL),
    CONSTRAINT site_commerce_collection_translations_check1 CHECK ((title IS NULL OR length(btrim(title)) > 0) AND (description IS NULL OR length(btrim(description)) > 0) AND (seo_title IS NULL OR length(btrim(seo_title)) > 0) AND (seo_description IS NULL OR length(btrim(seo_description)) > 0))
);

CREATE INDEX ON site_commerce_collection_translations (tenant_id, site_id, locale);

CREATE TABLE site_commerce_markets (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id int NOT NULL,
    public_id uuid DEFAULT uuidv7() NOT NULL UNIQUE,
    handle varchar(100) NOT NULL CHECK (handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    price_list_id int,
    is_default bool NOT NULL,
    enabled bool NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    vat_code_override varchar(2),
    net_price_rounding_increment numeric(8,2) CHECK (net_price_rounding_increment > 0 AND net_price_rounding_increment <= 1000),
    CHECK (NOT is_default OR enabled),
    UNIQUE (id, site_id, tenant_id),
    FOREIGN KEY (price_list_id, tenant_id) REFERENCES product_price_lists(id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES site_commerce_settings(site_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT site_commerce_markets_site_id_tenant_id_fkey1 FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, tenant_id, handle)
);

CREATE INDEX ON site_commerce_markets (price_list_id, tenant_id);

CREATE UNIQUE INDEX ON site_commerce_markets (site_id, tenant_id) WHERE is_default;

CREATE TABLE site_commerce_discounts (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id int,
    public_id uuid NOT NULL UNIQUE,
    code varchar(40) NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$'),
    percentage smallint NOT NULL,
    minimum_product_gross numeric(15,2) CHECK (minimum_product_gross IS NULL OR minimum_product_gross >= 0),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    enabled bool NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    market_id int,
    includes_free_shipping bool DEFAULT false NOT NULL,
    collection_id int,
    all_sales_channels bool DEFAULT false NOT NULL,
    amount numeric(15,2),
    buy_quantity smallint,
    pay_quantity smallint,
    PRIMARY KEY (id, tenant_id),
    CHECK (NOT all_sales_channels OR site_id IS NULL AND market_id IS NULL AND collection_id IS NULL),
    CONSTRAINT site_commerce_discounts_check1 CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT site_commerce_discounts_check2 CHECK (((percentage >= 0) AND (percentage <= 100) AND ((amount IS NULL) OR ((amount > (0)::numeric) AND (percentage = 0))) AND (((buy_quantity IS NULL) AND (pay_quantity IS NULL) AND ((percentage > 0) OR (amount IS NOT NULL) OR includes_free_shipping)) OR ((buy_quantity IS NOT NULL) AND (pay_quantity IS NOT NULL) AND ((buy_quantity >= 2) AND (buy_quantity <= 99)) AND (pay_quantity >= 1) AND (pay_quantity < buy_quantity) AND (percentage = 0) AND (amount IS NULL) AND (site_id IS NOT NULL) AND (market_id IS NOT NULL))))),
    CONSTRAINT site_commerce_discounts_check3 CHECK (site_id IS NULL AND market_id IS NULL OR site_id IS NOT NULL AND market_id IS NOT NULL),
    FOREIGN KEY (collection_id, site_id, tenant_id) REFERENCES site_commerce_collections(id, site_id, tenant_id),
    UNIQUE (id, market_id, site_id, tenant_id),
    FOREIGN KEY (market_id, site_id, tenant_id) REFERENCES site_commerce_markets(id, site_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (market_id, tenant_id, code),
    FOREIGN KEY (site_id, tenant_id) REFERENCES site_commerce_settings(site_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT site_commerce_discounts_site_id_tenant_id_fkey1 FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON site_commerce_discounts (market_id, tenant_id, enabled, starts_at, ends_at);

CREATE UNIQUE INDEX ON site_commerce_discounts (tenant_id, code) WHERE site_id IS NULL AND market_id IS NULL;

CREATE TABLE site_commerce_market_countries (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    market_id int NOT NULL,
    country_code char(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    created_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (market_id, tenant_id, country_code),
    UNIQUE (site_id, tenant_id, country_code),
    FOREIGN KEY (market_id, site_id, tenant_id) REFERENCES site_commerce_markets(id, site_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE site_commerce_market_locales (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    market_id int NOT NULL,
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    is_default bool NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (market_id, site_id, tenant_id) REFERENCES site_commerce_markets(id, site_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (market_id, tenant_id, locale)
);

CREATE UNIQUE INDEX ON site_commerce_market_locales (market_id, tenant_id) WHERE is_default;

CREATE TABLE site_commerce_shipping_methods (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    amount numeric(15,2) NOT NULL CHECK (amount >= 0),
    free_from_amount numeric(15,2) CHECK (free_from_amount IS NULL OR free_from_amount >= 0),
    sort_order smallint NOT NULL CHECK (sort_order >= 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    market_id int NOT NULL,
    delivery_method varchar(32),
    carrier_code varchar(30),
    pickup_details varchar(1000),
    weight_threshold_grams int,
    amount_above_weight numeric(15,2),
    countries text[] DEFAULT '{}' NOT NULL,
    CHECK (delivery_method IS NULL AND (carrier_code IS NULL OR carrier_code = 'DHL') OR delivery_method IS NOT NULL AND carrier_code IN ('BRING', 'POSTNORD') AND NOT (carrier_code = 'POSTNORD' AND delivery_method = 'MAILBOX')),
    CONSTRAINT site_commerce_shipping_methods_check1 CHECK (weight_threshold_grams IS NULL AND amount_above_weight IS NULL OR weight_threshold_grams IS NOT NULL AND amount_above_weight IS NOT NULL AND weight_threshold_grams > 0 AND amount_above_weight >= 0 AND delivery_method IS NOT NULL),
    CONSTRAINT site_commerce_shipping_methods_check2 CHECK (pickup_details IS NULL OR length(TRIM(BOTH FROM pickup_details)) > 0 AND delivery_method IS NULL AND carrier_code IS NULL AND amount = 0 AND free_from_amount IS NULL),
    FOREIGN KEY (market_id, site_id, tenant_id) REFERENCES site_commerce_markets(id, site_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (site_id, tenant_id) REFERENCES site_commerce_settings(site_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT site_commerce_shipping_methods_site_id_tenant_id_fkey1 FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON site_commerce_shipping_methods (market_id, tenant_id, carrier_code, delivery_method) WHERE delivery_method IS NOT NULL;

CREATE INDEX ON site_commerce_shipping_methods (market_id, tenant_id, sort_order, id);

CREATE TABLE site_credentials (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    site_id int NOT NULL,
    public_id uuid NOT NULL UNIQUE,
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    environment varchar(20) NOT NULL,
    token_hash bytea NOT NULL UNIQUE,
    scopes text NOT NULL,
    expires_at date,
    revoked_at timestamptz,
    last_used_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE site_storefront_setups (
    site_id int PRIMARY KEY,
    tenant_id int NOT NULL,
    repository_name varchar(100) UNIQUE,
    repository_id bigint UNIQUE,
    phase varchar(32) DEFAULT 'FAILED' NOT NULL,
    credential_public_id uuid,
    worker_name varchar(63),
    worker_url varchar(255),
    management_type varchar(16) DEFAULT 'MANAGED' NOT NULL,
    safe_deployment bool DEFAULT false NOT NULL,
    deployment_run_id bigint,
    deployment_attempt int,
    CHECK (management_type = 'MANAGED' AND repository_name IS NOT NULL OR management_type = 'EXTERNAL' AND repository_name IS NULL AND repository_id IS NULL AND credential_public_id IS NULL AND worker_name IS NULL),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON site_storefront_setups (tenant_id);

CREATE TABLE sub_accounts (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    account_number varchar(10) NOT NULL,
    name varchar(150) NOT NULL,
    sales_customer_type varchar(10),
    PRIMARY KEY (id, tenant_id),
    UNIQUE (id, tenant_id, account_number)
);

CREATE UNIQUE INDEX ON sub_accounts (tenant_id, account_number, lower(name));

CREATE TABLE adyen_balance_accounts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_onboarding_id int NOT NULL,
    balance_account_id text NOT NULL UNIQUE,
    default_currency_code char(3) NOT NULL CHECK (default_currency_code ~ '^[A-Z]{3}$'),
    status text,
    status_observed_at timestamptz,
    clearing_sub_account_id int NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (clearing_sub_account_id, tenant_id) REFERENCES sub_accounts,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, adyen_onboarding_id),
    UNIQUE (tenant_id, clearing_sub_account_id)
);

CREATE TABLE adyen_liable_accounts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    account_holder_id text NOT NULL UNIQUE,
    balance_account_id text NOT NULL UNIQUE,
    default_currency_code char(3) NOT NULL CHECK (default_currency_code ~ '^[A-Z]{3}$'),
    clearing_sub_account_id int NOT NULL,
    company_bank_id int NOT NULL,
    transactions_from timestamptz NOT NULL,
    last_synced_at timestamptz,
    approval_reference varchar(500) NOT NULL CHECK (length(TRIM(BOTH FROM approval_reference)) > 0),
    commission_vat_code varchar(3) NOT NULL CHECK (commission_vat_code IN ('0', '3')),
    fee_vat_code varchar(3) NOT NULL CHECK (fee_vat_code IN ('0', '1', '86')),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (clearing_sub_account_id, tenant_id) REFERENCES sub_accounts,
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, clearing_sub_account_id)
);

CREATE TABLE share_investments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(150) CHECK (length(btrim(name)) > 0),
    ticker varchar(20),
    isin varchar(12),
    organization_number varchar(9),
    instrument_type varchar(20),
    currency varchar(3) NOT NULL,
    within_exemption_method bool,
    status varchar(15) DEFAULT 'OPEN' NOT NULL,
    asset_account_number varchar(10) NOT NULL,
    sub_account_id int,
    company_bank_id int,
    quantity numeric(20,6) DEFAULT 0 NOT NULL CHECK (quantity >= 0),
    cost_price numeric(15,2) DEFAULT 0 NOT NULL CHECK (cost_price >= 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    security_id int REFERENCES security_instruments,
    tax_assessment_year int CHECK (tax_assessment_year >= 1900 AND tax_assessment_year <= 9999),
    CHECK (security_id IS NOT NULL AND name IS NULL AND isin IS NULL AND instrument_type IS NULL AND ticker IS NULL AND organization_number IS NULL OR security_id IS NULL AND name IS NOT NULL AND instrument_type IS NOT NULL),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (sub_account_id, tenant_id) REFERENCES sub_accounts
);

CREATE UNIQUE INDEX ON share_investments (tenant_id, isin) WHERE isin IS NOT NULL;

CREATE UNIQUE INDEX ON share_investments (tenant_id, lower(name));

CREATE INDEX ON share_investments (tenant_id, name);

CREATE UNIQUE INDEX ON share_investments (tenant_id, security_id) WHERE security_id IS NOT NULL;

CREATE TABLE subscriptions (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    customer_id int NOT NULL,
    start_date date NOT NULL,
    interval_months smallint NOT NULL CHECK (interval_months >= 1 AND interval_months <= 120),
    billing_timing varchar(20) NOT NULL,
    output_mode varchar(20) NOT NULL,
    active bool DEFAULT true NOT NULL,
    days_until_due smallint NOT NULL CHECK (days_until_due >= 0 AND days_until_due <= 3000),
    currency_code varchar(3) NOT NULL,
    invoice_comment text,
    internal_comment text,
    invoice_email varchar(100) CHECK (invoice_email IS NULL OR invoice_email = lower(invoice_email)),
    send_ehf bool DEFAULT false NOT NULL,
    project_id int,
    next_billing_date date NOT NULL,
    next_billing_period_start date NOT NULL,
    next_billing_period_end date NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    automatic_billing_generation bool DEFAULT false NOT NULL,
    agreement_id int,
    period_alignment varchar DEFAULT 'CALENDAR_BOUNDARY' NOT NULL,
    billing_lead_days smallint DEFAULT 0 NOT NULL CHECK (billing_lead_days >= 0 AND billing_lead_days <= 3000),
    billing_day_of_month smallint CHECK (billing_day_of_month IS NULL OR billing_day_of_month >= 1 AND billing_day_of_month <= 31),
    buyer_reference varchar(255),
    payment_provider_shopper_reference varchar(128),
    stored_payment_method_reference varchar(128),
    management_token varchar(64),
    renewal_anchor_date date,
    card_setup_reference varchar(128),
    card_setup_expires_at timestamptz,
    card_billing_after_id int DEFAULT 0 NOT NULL,
    card_setup_consent_at timestamptz,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (agreement_id, tenant_id) REFERENCES agreements ON DELETE SET NULL (agreement_id),
    CHECK (next_billing_period_start <= next_billing_period_end),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects
);

CREATE INDEX ON subscriptions (agreement_id) WHERE agreement_id IS NOT NULL;

CREATE INDEX ON subscriptions (customer_id);

CREATE UNIQUE INDEX ON subscriptions (management_token) WHERE management_token IS NOT NULL;

CREATE INDEX ON subscriptions (project_id);

CREATE INDEX ON subscriptions (tenant_id, active, next_billing_date, id);

CREATE TABLE reai_system_subscriptions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    subscription_id int,
    expired bool DEFAULT true NOT NULL,
    subscription_tenant_id int GENERATED ALWAYS AS (
CASE
    WHEN subscription_id IS NULL THEN NULL
    ELSE 1239
END) STORED,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (subscription_id, subscription_tenant_id) REFERENCES subscriptions
);

CREATE INDEX ON reai_system_subscriptions (expired);

CREATE TABLE subscription_billing_generation_queue (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    subscription_id int NOT NULL,
    billing_date date NOT NULL,
    status varchar(20) DEFAULT 'PENDING' NOT NULL,
    attempts smallint DEFAULT 0 NOT NULL CHECK (attempts >= 0 AND attempts <= 5),
    last_error text,
    started_at timestamptz,
    processed_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, subscription_id, billing_date),
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions ON DELETE CASCADE
);

CREATE INDEX ON subscription_billing_generation_queue (status, created_at, id) WHERE status = 'PENDING';

CREATE TABLE subscription_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    subscription_id int NOT NULL,
    row_number smallint NOT NULL,
    item_name varchar(255) NOT NULL,
    comment text,
    quantity numeric(10,2) NOT NULL CHECK (quantity >= 0),
    unit_price numeric(10,2) NOT NULL,
    discount smallint CHECK (discount IS NULL OR discount >= 0 AND discount <= 100),
    vat_code varchar(5) NOT NULL,
    variant_id int,
    one_time bool DEFAULT false NOT NULL,
    UNIQUE (subscription_id, row_number),
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions ON DELETE CASCADE,
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE SET NULL (variant_id)
);

CREATE TABLE subscription_service_recipients (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    subscription_id int NOT NULL,
    company_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (company_id, tenant_id) REFERENCES companies,
    UNIQUE (subscription_id, company_id),
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions ON DELETE CASCADE
);

CREATE INDEX ON subscription_service_recipients (tenant_id, company_id);

CREATE TABLE suppliers (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    company_id int,
    person_id int,
    archived bool DEFAULT false NOT NULL,
    email varchar(255) CHECK (email IS NULL OR email = lower(email)) CONSTRAINT suppliers_email_check1 CHECK (email IS NULL OR length(btrim(email)) > 0),
    phone varchar(50) CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{4,14}$'),
    iban varchar(34) CHECK (iban IS NULL OR length(btrim(iban)) > 0),
    bank_account_number varchar(50) CHECK (bank_account_number IS NULL OR length(btrim(bank_account_number)) > 0),
    swift_code varchar(11) CHECK (swift_code IS NULL OR length(btrim(swift_code)) > 0),
    auto_register_payment_threshold numeric(19,2) CHECK (auto_register_payment_threshold IS NULL OR auto_register_payment_threshold > 0),
    number varchar(50) CHECK (number IS NULL OR btrim(number) <> ''),
    emails_in_english bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id),
    CHECK (company_id IS NULL <> (person_id IS NULL)),
    FOREIGN KEY (company_id, tenant_id) REFERENCES companies,
    FOREIGN KEY (person_id, tenant_id) REFERENCES persons ON DELETE CASCADE
);

CREATE INDEX ON suppliers (company_id);

CREATE INDEX ON suppliers USING gin (number gin_trgm_ops) WHERE number IS NOT NULL;

CREATE INDEX ON suppliers (person_id);

CREATE INDEX ON suppliers (tenant_id, archived, created_at DESC, id DESC);

CREATE UNIQUE INDEX ON suppliers (tenant_id, company_id) WHERE company_id IS NOT NULL;

CREATE UNIQUE INDEX ON suppliers (tenant_id, number) WHERE number IS NOT NULL;

CREATE UNIQUE INDEX ON suppliers (tenant_id, person_id) WHERE person_id IS NOT NULL;

CREATE TABLE product_suppliers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    supplier_id int NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers ON DELETE CASCADE,
    UNIQUE (tenant_id, product_id, supplier_id)
);

CREATE INDEX ON product_suppliers (tenant_id, product_id, id);

CREATE INDEX ON product_suppliers (tenant_id, supplier_id, product_id);

CREATE TABLE reconciliation_rules (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    match_text varchar(255) NOT NULL,
    account_number varchar(50) NOT NULL,
    description text NOT NULL,
    enabled bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    vat_code varchar(10) DEFAULT '0' NOT NULL,
    supplier_id int,
    documentation_email varchar(254),
    documentation_email_text text,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers
);

CREATE INDEX ON reconciliation_rules (tenant_id, enabled);

CREATE TABLE supplier_contact_persons (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    supplier_id int NOT NULL,
    name varchar(75) NOT NULL CHECK (btrim(name) <> ''),
    email text CHECK (email IS NULL OR email = lower(email)) CONSTRAINT supplier_contact_persons_email_check1 CHECK (email IS NULL OR btrim(email) <> ''),
    phone text CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{4,14}$'),
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers ON DELETE CASCADE
);

CREATE INDEX ON supplier_contact_persons (tenant_id, supplier_id, id);

CREATE TABLE tax_cards (
    id int GENERATED BY DEFAULT AS IDENTITY,
    employee_id int NOT NULL,
    income_year varchar(10),
    additional_info jsonb,
    result_for_tax_card varchar(255),
    issued_date date,
    tax_card_identifier varchar(255),
    manual_percentage_rate numeric(7,4) CHECK (manual_percentage_rate IS NULL OR manual_percentage_rate >= 0 AND manual_percentage_rate <= 100),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    last_checked_on date,
    last_checked_national_identity_number varchar(11),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE
);

CREATE INDEX ON tax_cards (tenant_id, employee_id, income_year, id DESC);

CREATE TABLE tax_debt_claim_snapshots (
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    open_claim_fetched_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    period_year int,
    period_from date,
    period_to date,
    due_date date NOT NULL,
    is_open_claim bool DEFAULT false NOT NULL,
    claim_identifier text NOT NULL,
    due_date_identifier text NOT NULL,
    claim_type text NOT NULL,
    claim_group text,
    title text NOT NULL,
    period_description text,
    technical_term_name text,
    original_amount numeric(15,2) NOT NULL,
    remaining_amount numeric(15,2) NOT NULL,
    estimated_interest numeric(15,2),
    payment_reference text,
    recipient_account_number text,
    recipient_account_owner_name varchar(255),
    kid_number varchar(64),
    UNIQUE (tenant_id, due_date_identifier)
);

CREATE INDEX ON tax_debt_claim_snapshots (tenant_id, is_open_claim, due_date);

CREATE INDEX ON tax_debt_claim_snapshots (tenant_id, period_year, period_from, period_to);

CREATE TABLE tax_debt_payment_placement_snapshots (
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_date date NOT NULL,
    placed_date date,
    payment_identifier text NOT NULL,
    due_date_identifier text NOT NULL,
    amount numeric(15,2) NOT NULL,
    UNIQUE (tenant_id, payment_identifier, due_date_identifier)
);

CREATE INDEX ON tax_debt_payment_placement_snapshots (tenant_id, due_date_identifier);

CREATE TABLE tax_debt_payout_claim_snapshots (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payout_identifier text NOT NULL,
    due_date_identifier text NOT NULL,
    payout_date date NOT NULL,
    amount numeric(15,2) NOT NULL,
    original_amount numeric(15,2) NOT NULL,
    remaining_amount numeric(15,2) NOT NULL,
    claim_type text,
    period_year int,
    period_from date,
    period_to date,
    period_description text,
    technical_term_name text,
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, payout_identifier, due_date_identifier)
);

CREATE INDEX ON tax_debt_payout_claim_snapshots (tenant_id, period_year, period_from, period_to);

CREATE TABLE tax_debt_unallocated_payment_snapshots (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_identifier text NOT NULL,
    payment_date date NOT NULL,
    amount numeric(15,2) NOT NULL,
    payment_type text,
    recipient_account_owner_name text,
    recipient_account_number text,
    recipient_iban text,
    recipient_bank_information text,
    paid_from_account_owner_name text,
    paid_from_account_number text,
    paid_from_iban text,
    paid_from_bank_information text,
    paid_from_kid_number text,
    paid_from_message text,
    is_open_payment bool DEFAULT true NOT NULL,
    open_payment_fetched_at timestamptz NOT NULL,
    fetched_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, payment_identifier)
);

CREATE INDEX ON tax_debt_unallocated_payment_snapshots (tenant_id, is_open_payment, payment_date);

CREATE TABLE tax_return_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    fiscal_year smallint NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON tax_return_attachments (id, tenant_id, fiscal_year);

CREATE INDEX ON tax_return_attachments (tenant_id, fiscal_year);

CREATE TABLE tax_return_fund_account (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    source_id text,
    source_fingerprint text,
    insurer_name text NOT NULL,
    insurer_organization_number varchar(9),
    account_number text NOT NULL,
    within_exemption_method bool NOT NULL,
    share_gain numeric(15,2) NOT NULL CHECK (share_gain >= 0),
    share_loss numeric(15,2) NOT NULL CHECK (share_loss >= 0),
    interest_gain numeric(15,2) NOT NULL CHECK (interest_gain >= 0),
    interest_loss numeric(15,2) NOT NULL CHECK (interest_loss >= 0),
    tax_free_withdrawal numeric(15,2) NOT NULL CHECK (tax_free_withdrawal >= 0),
    taxable_result_in_business_income numeric(15,2) NOT NULL,
    reconciliation_note text NOT NULL,
    evidence_attachment_id int NOT NULL,
    excluded bool DEFAULT false NOT NULL,
    FOREIGN KEY (evidence_attachment_id, tenant_id, fiscal_year) REFERENCES tax_return_attachments(id, tenant_id, fiscal_year),
    UNIQUE (tenant_id, fiscal_year, insurer_name, account_number),
    UNIQUE (tenant_id, fiscal_year, source_id)
);

CREATE TABLE tax_return_group_contribution (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    direction varchar(10) NOT NULL,
    counterparty_organization_number varchar(9) NOT NULL,
    counterparty_name varchar(255) NOT NULL,
    group_affiliation_type varchar(20) NOT NULL,
    group_affiliation_description varchar(500),
    voting_rights_restriction bool DEFAULT false NOT NULL,
    voting_rights_restriction_reason varchar(500),
    acquisition_date date,
    amount numeric(15,2) NOT NULL,
    amount_without_tax_effect numeric(15,2) DEFAULT 0 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (amount > 0 AND amount_without_tax_effect >= 0 AND amount_without_tax_effect <= amount)
);

CREATE UNIQUE INDEX ON tax_return_group_contribution (tenant_id, fiscal_year, direction, counterparty_organization_number);

CREATE TABLE tax_return_other_info (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    aid_scheme_skattefunn bool DEFAULT false NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    loss_carryforward_from_previous_years numeric(15,2),
    fixed_asset_temporary_difference_accounting_value numeric(15,2),
    fixed_asset_temporary_difference_tax_value numeric(15,2),
    non_listed_share_formuesverdi_total numeric(15,2),
    share_issue_and_formation_cost_deduction numeric(15,2),
    operating_asset_wealth_value numeric(15,2),
    group_contribution_confirmed_by_auditor bool,
    debt_not_deductible_for_wealth numeric(15,2),
    accounting_provisions jsonb,
    reviewed_loss_carryforward_from_previous_years numeric(15,2)
);

CREATE UNIQUE INDEX ON tax_return_other_info (tenant_id, fiscal_year);

CREATE TABLE tax_return_rented_business_property_info (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    property_identifier varchar(80) NOT NULL,
    rented_area int NOT NULL,
    property_type varchar(80) NOT NULL CHECK (property_type IN ('kontor', 'butikkEllerKjoepesenter', 'lager', 'industri', 'verksted', 'hotell', 'fritidseiendom', 'tomtGrunnarealHovedfunksjon', 'utendoersHavneareal', 'utendoersParkeringsplass', 'annet')),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    rented bool DEFAULT true NOT NULL,
    registered_manually bool DEFAULT false NOT NULL,
    municipality_number varchar(4),
    holding_number int,
    sub_holding_number int,
    lease_number int,
    section_number int,
    total_area int,
    months_rented smallint,
    ownership_percent numeric(6,2),
    gross_rent_income_year numeric(15,2),
    gross_rent_previous_year numeric(15,2),
    gross_rent_two_years_ago numeric(15,2),
    CHECK (NOT registered_manually OR rented AND rented_area > 0 AND rented_area::bigint * 2 >= total_area AND months_rented IS NOT NULL AND gross_rent_income_year IS NOT NULL OR NOT rented AND rented_area = 0 AND months_rented IS NULL AND gross_rent_income_year IS NULL AND gross_rent_previous_year IS NULL AND gross_rent_two_years_ago IS NULL),
    CONSTRAINT tax_return_rented_business_property_info_check1 CHECK (btrim(property_identifier) <> '' AND rented_area >= 0 AND (municipality_number IS NULL OR municipality_number ~ '^[0-9]{4}$') AND (holding_number IS NULL OR holding_number > 0) AND (sub_holding_number IS NULL OR sub_holding_number > 0) AND (lease_number IS NULL OR lease_number > 0) AND (section_number IS NULL OR section_number > 0) AND (total_area IS NULL OR total_area > 0) AND (total_area IS NULL OR rented_area <= total_area) AND (months_rented IS NULL OR months_rented >= 1 AND months_rented <= 12) AND (ownership_percent IS NULL OR ownership_percent > 0 AND ownership_percent <= 100) AND (gross_rent_income_year IS NULL OR gross_rent_income_year > 0) AND (gross_rent_previous_year IS NULL OR gross_rent_previous_year > 0) AND (gross_rent_two_years_ago IS NULL OR gross_rent_two_years_ago > 0)),
    CONSTRAINT tax_return_rented_business_property_info_check2 CHECK (NOT registered_manually OR municipality_number IS NOT NULL AND holding_number IS NOT NULL AND sub_holding_number IS NOT NULL AND total_area IS NOT NULL AND ownership_percent IS NOT NULL)
);

CREATE UNIQUE INDEX ON tax_return_rented_business_property_info (tenant_id, fiscal_year, property_identifier);

CREATE TABLE tax_return_source_snapshots (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    skattemelding_document_id varchar(255) NOT NULL,
    partsnummer varchar(255) NOT NULL,
    primary_document_type varchar(80) NOT NULL,
    source_type varchar(32) DEFAULT 'CURRENT' NOT NULL,
    response_xml xml NOT NULL,
    primary_document_xml xml NOT NULL,
    business_specification_xml xml,
    extended_guidance_count int DEFAULT 0 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);

CREATE UNIQUE INDEX tax_return_source_snapshots_tenant_id_fiscal_year_source_t_idx1 ON tax_return_source_snapshots (tenant_id, fiscal_year, source_type) WHERE source_type = 'FINAL';

CREATE INDEX ON tax_return_source_snapshots (tenant_id, fiscal_year, source_type, created_at DESC);

CREATE TABLE tax_return_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    status varchar(30) NOT NULL,
    altinn_instance_id varchar(255),
    skattemelding_document_id varchar(255),
    complete_at timestamptz,
    submitted_at timestamptz,
    approver varchar(255),
    email varchar(255) CHECK (email IS NULL OR length(btrim(email)) > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    receipt_xml xml,
    receipt_stored_at timestamptz,
    submitted_in_other_system bool DEFAULT false NOT NULL,
    submission_status_checked_at timestamptz,
    PRIMARY KEY (id, tenant_id)
);

CREATE INDEX ON tax_return_submissions (tenant_id, fiscal_year, created_at DESC);

CREATE TABLE tax_return_debug_payloads (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    draft_skattemelding_xml xml,
    generated_skattemelding_xml xml,
    naeringsspesifikasjon_xml xml,
    request_xml xml,
    validation_response_xml xml,
    latest_utkast_response_xml xml,
    latest_fastsatt_response_xml xml,
    validation_error text,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    submission_id int NOT NULL,
    FOREIGN KEY (submission_id, tenant_id) REFERENCES tax_return_submissions ON DELETE CASCADE
);

CREATE INDEX ON tax_return_debug_payloads (tenant_id, submission_id, created_at DESC);

CREATE TABLE tax_return_validations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    fiscal_year smallint NOT NULL,
    valid bool NOT NULL,
    error_count int DEFAULT 0 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (tenant_id, fiscal_year)
);

CREATE TABLE tenant_email_reply_to (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    address varchar(254) NOT NULL CHECK (address = lower(btrim(address)) AND address <> ''),
    label varchar(100) DEFAULT '' NOT NULL,
    is_primary bool DEFAULT false NOT NULL,
    UNIQUE (tenant_id, address)
);

CREATE UNIQUE INDEX ON tenant_email_reply_to (tenant_id) WHERE is_primary;

CREATE TABLE tenant_email_sender (
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    verified_at timestamptz,
    checked_at timestamptz,
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    status text DEFAULT 'UNVERIFIED' NOT NULL,
    address text NOT NULL CHECK (address = lower(address) AND address ~~ '_%@_%'),
    name text,
    last_error text,
    dns_records jsonb,
    ownership_token text
);

CREATE UNIQUE INDEX ON tenant_email_sender (split_part(address, '@', 2)) WHERE status <> 'UNVERIFIED';

CREATE TABLE tenant_email_template (
    updated_at timestamptz DEFAULT now() NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    type text NOT NULL,
    subject text NOT NULL CHECK (length(subject) >= 1 AND length(subject) <= 300),
    body text NOT NULL CHECK (length(body) >= 1 AND length(body) <= 20000),
    PRIMARY KEY (tenant_id, type)
);

CREATE TABLE tenant_links (
    id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    to_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    authorities text DEFAULT '' NOT NULL,
    UNIQUE (from_tenant_id, to_tenant_id)
);

CREATE INDEX ON tenant_links (to_tenant_id);

CREATE TABLE tenant_logo (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    file_data bytea NOT NULL
);

CREATE TABLE timesheet_approval_group (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    month date NOT NULL CHECK (EXTRACT(day FROM month) = 1),
    project_id int,
    status text NOT NULL,
    revision int DEFAULT 1 NOT NULL,
    reason text DEFAULT '' NOT NULL,
    submitted_at timestamptz NOT NULL,
    needs_resubmission bool DEFAULT false NOT NULL,
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    UNIQUE NULLS NOT DISTINCT (tenant_id, employee_id, month, project_id)
);

CREATE TABLE timesheet_approval_policy (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    effective_month date NOT NULL CHECK (EXTRACT(day FROM effective_month) = 1),
    enabled bool NOT NULL,
    actor_email text NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (tenant_id, effective_month)
);

CREATE TABLE timesheet_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    project_timer_enabled bool DEFAULT false NOT NULL
);

CREATE TABLE user_invitations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    token uuid DEFAULT gen_random_uuid() NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    approved_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    email varchar(255) NOT NULL CHECK (length(btrim(email)) > 0) CONSTRAINT user_invitations_email_check1 CHECK (email = lower(email)),
    authorities text NOT NULL,
    CHECK (expires_at > created_at),
    CONSTRAINT user_invitations_check1 CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ON user_invitations (tenant_id, email);

CREATE TABLE users (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    email varchar(128) NOT NULL CHECK (email = lower(email)) UNIQUE,
    password_hash varchar(60) NOT NULL,
    is_enabled bool DEFAULT true NOT NULL,
    is_locked bool DEFAULT false NOT NULL,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    last_tenant_id int REFERENCES tenants ON DELETE SET NULL,
    authorities text DEFAULT '' NOT NULL,
    phone varchar(50) CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),
    full_name varchar(255) NOT NULL,
    must_set_password bool DEFAULT false NOT NULL,
    preferred_language varchar(8) CHECK (preferred_language IS NULL OR preferred_language IN ('no', 'en'))
);

CREATE INDEX ON users (authorities);

CREATE TABLE access_tokens (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int REFERENCES tenants ON DELETE CASCADE,
    token_hash bytea NOT NULL,
    title varchar(120) NOT NULL,
    scopes text NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    expires_at date,
    last_used_at timestamptz,
    user_id int REFERENCES users ON DELETE CASCADE,
    type varchar(10) DEFAULT 'TENANT' NOT NULL,
    created_by_user_id int REFERENCES users ON DELETE SET NULL,
    CHECK (type = 'TENANT' AND tenant_id IS NOT NULL AND user_id IS NULL OR type = 'USER' AND user_id IS NOT NULL OR type = 'REFRESH' AND user_id IS NOT NULL AND tenant_id IS NULL)
);

CREATE INDEX ON access_tokens (created_by_user_id);

CREATE INDEX ON access_tokens (tenant_id);

CREATE UNIQUE INDEX ON access_tokens (token_hash);

CREATE INDEX ON access_tokens (type, tenant_id);

CREATE INDEX ON access_tokens (type, user_id);

CREATE INDEX ON access_tokens (user_id);

CREATE TABLE accountant_client_assignees (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    accountant_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    client_tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    assigned_user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
    UNIQUE (accountant_tenant_id, client_tenant_id)
);

CREATE INDEX ON accountant_client_assignees (accountant_tenant_id, assigned_user_id);

CREATE TABLE adyen_agreement_acceptances (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_onboarding_id int NOT NULL,
    agreement_type varchar(30) NOT NULL,
    adyen_document_id varchar(255) NOT NULL,
    accepted_by_user_id int NOT NULL REFERENCES users ON DELETE RESTRICT,
    accepted_at timestamptz DEFAULT now() NOT NULL,
    accepted_ip_address varchar(45),
    UNIQUE (adyen_onboarding_id, agreement_type, adyen_document_id),
    FOREIGN KEY (adyen_onboarding_id, tenant_id) REFERENCES adyen_onboardings(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE amelding_reporting_verifications (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reporting_period date NOT NULL CHECK (EXTRACT(day FROM reporting_period) = 1),
    organization_number varchar(9) NOT NULL,
    no_obligation bool NOT NULL,
    reason varchar(1000),
    recorded_by int NOT NULL REFERENCES users,
    recorded_at timestamptz DEFAULT now() NOT NULL,
    feedback_acknowledged bool
);

CREATE INDEX ON amelding_reporting_verifications (tenant_id, reporting_period, id DESC);

CREATE TABLE change_log (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    entity_id bigint,
    changed_at timestamptz NOT NULL,
    tenant_id int REFERENCES tenants ON DELETE CASCADE,
    changed_by_api bool DEFAULT false NOT NULL,
    changed_by_automation bool DEFAULT false NOT NULL,
    entity_name varchar(255) NOT NULL,
    operation varchar(20) NOT NULL,
    changed_by varchar(255),
    changed_by_api_key_type varchar(20) CHECK (changed_by_api_key_type IS NULL OR changed_by_api_key_type IN ('USER', 'TENANT')),
    changed_by_api_key_created_by varchar(255),
    details jsonb,
    actor_user_id int REFERENCES users ON DELETE SET NULL
);

CREATE INDEX ON change_log (tenant_id, entity_name, entity_id);

CREATE TABLE document_sign_requests (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    document_id int NOT NULL,
    signer_email varchar(320) NOT NULL,
    requester_email varchar(320) NOT NULL,
    token uuid DEFAULT uuidv7() NOT NULL,
    is_signed bool DEFAULT false NOT NULL,
    requested_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    signed_at timestamptz,
    signed_by_user_id int REFERENCES users ON DELETE SET NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    automatically_signed bool DEFAULT false NOT NULL,
    email_sent_at timestamptz,
    FOREIGN KEY (document_id, tenant_id) REFERENCES documents ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON document_sign_requests (document_id, lower(signer_email));

CREATE INDEX ON document_sign_requests (document_id, requested_at, id);

CREATE TABLE efaktura_tenant_configs (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    payout_bank_account_id int NOT NULL,
    accepted_by_user_id int REFERENCES users ON DELETE SET NULL,
    accepted_by_email varchar(255) NOT NULL,
    accepted_at timestamptz DEFAULT now() NOT NULL,
    terms_version varchar(30) NOT NULL,
    accepted_ip_address varchar(45),
    accepted_user_agent varchar(500),
    enabled bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    disabled_at timestamptz,
    PRIMARY KEY (id, tenant_id),
    CHECK (length(btrim(accepted_by_email)) > 0 AND length(btrim(terms_version)) > 0),
    FOREIGN KEY (payout_bank_account_id, tenant_id) REFERENCES company_bank
);

CREATE TABLE google_merchant_credentials (
    site_id uuid NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    access_ciphertext text NOT NULL,
    refresh_ciphertext text NOT NULL,
    expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz,
    connected_at timestamptz DEFAULT now() NOT NULL,
    connected_by int REFERENCES users ON DELETE SET NULL,
    needs_reconnect bool DEFAULT false NOT NULL,
    PRIMARY KEY (site_id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE idporten_sessions (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    state varchar(255) NOT NULL,
    nonce varchar(255) NOT NULL,
    code_verifier varchar(255) NOT NULL,
    access_token text,
    id_token text,
    refresh_token text,
    expires_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    refresh_expires_at timestamptz,
    next_refresh_at timestamptz,
    user_id int REFERENCES users ON DELETE CASCADE,
    idporten_subject text,
    PRIMARY KEY (id, tenant_id)
);

CREATE UNIQUE INDEX ON idporten_sessions (user_id) WHERE user_id IS NOT NULL AND access_token IS NOT NULL;

CREATE UNIQUE INDEX idporten_sessions_user_id_idx1 ON idporten_sessions (user_id) WHERE user_id IS NOT NULL AND access_token IS NULL;

CREATE TABLE invoice_reception_notification_settings (
    tenant_id int PRIMARY KEY REFERENCES tenants ON DELETE CASCADE,
    enabled bool DEFAULT false NOT NULL,
    recipient varchar(254),
    configured_by int REFERENCES users ON DELETE SET NULL,
    version int DEFAULT 1 NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL
);

CREATE TABLE mcp_connections (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
    client_id varchar(100) NOT NULL REFERENCES mcp_clients,
    resource varchar(512) NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    expires_at timestamptz NOT NULL
);

CREATE INDEX ON mcp_connections (client_id);

CREATE INDEX ON mcp_connections (user_id);

CREATE TABLE mcp_authorization_codes (
    code_hash bytea PRIMARY KEY,
    connection_id int NOT NULL REFERENCES mcp_connections ON DELETE CASCADE,
    redirect_uri text NOT NULL,
    challenge varchar(43) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);

CREATE INDEX ON mcp_authorization_codes (connection_id);

CREATE TABLE mcp_tokens (
    token_hash bytea PRIMARY KEY,
    connection_id int NOT NULL REFERENCES mcp_connections ON DELETE CASCADE,
    kind varchar(7) NOT NULL CHECK (kind IN ('access', 'refresh')),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);

CREATE INDEX ON mcp_tokens (connection_id);

CREATE TABLE meta_commerce_credentials (
    site_id uuid NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    token_ciphertext text NOT NULL,
    expires_at timestamptz NOT NULL,
    connected_at timestamptz DEFAULT now() NOT NULL,
    connected_by int REFERENCES users ON DELETE SET NULL,
    needs_reconnect bool DEFAULT false NOT NULL,
    PRIMARY KEY (site_id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(public_id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE password_reset_tokens (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
    token uuid DEFAULT gen_random_uuid() NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX ON password_reset_tokens (user_id);

CREATE TABLE site_github_access (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id int NOT NULL,
    tenant_id int NOT NULL,
    user_id int NOT NULL REFERENCES users,
    github_user_id bigint NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'GRANTED', 'FAILED')),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (site_id, user_id)
);

CREATE INDEX ON site_github_access (tenant_id);

CREATE TABLE site_github_accounts (
    user_id int PRIMARY KEY REFERENCES users,
    github_user_id bigint NOT NULL,
    github_login varchar(39) NOT NULL
);

CREATE TABLE user_tenants (
    user_id int NOT NULL REFERENCES users,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    authorities text DEFAULT '' NOT NULL,
    engagement_responsible bool DEFAULT false NOT NULL,
    UNIQUE (user_id, tenant_id)
);

CREATE INDEX ON user_tenants (tenant_id);

CREATE TABLE crm_leads (
    converted_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    converted_customer_id int,
    follow_up_at date,
    owner_user_id int,
    org_number int NOT NULL REFERENCES brreg_companies_compact ON DELETE RESTRICT,
    status varchar(32),
    notes text,
    email text CHECK (email IS NULL OR length(btrim(email)) > 0),
    phone text CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{4,14}$'),
    PRIMARY KEY (id, tenant_id),
    CHECK (status = 'CONVERTED' AND converted_customer_id IS NOT NULL AND converted_at IS NOT NULL OR status IS DISTINCT FROM 'CONVERTED' AND converted_customer_id IS NULL AND converted_at IS NULL),
    FOREIGN KEY (owner_user_id, tenant_id) REFERENCES user_tenants(user_id, tenant_id) ON DELETE SET NULL (owner_user_id),
    FOREIGN KEY (tenant_id, converted_customer_id) REFERENCES customers(tenant_id, id),
    UNIQUE (tenant_id, org_number)
);

CREATE INDEX ON crm_leads (converted_customer_id) WHERE converted_customer_id IS NOT NULL;

CREATE INDEX ON crm_leads (org_number);

CREATE INDEX ON crm_leads (tenant_id, owner_user_id) WHERE owner_user_id IS NOT NULL;

CREATE INDEX ON crm_leads (tenant_id, phone) WHERE phone IS NOT NULL;

CREATE INDEX ON crm_leads (tenant_id, status, created_at DESC, id DESC);

CREATE TABLE crm_lead_contact_events (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    lead_id int NOT NULL,
    contacted_on date NOT NULL,
    author_employee_id int,
    source varchar(16) NOT NULL,
    note text CHECK (note IS NULL OR btrim(note) <> ''),
    author_name text,
    contacted_at timestamptz,
    FOREIGN KEY (author_employee_id, tenant_id) REFERENCES employees,
    FOREIGN KEY (lead_id, tenant_id) REFERENCES crm_leads ON DELETE CASCADE
);

CREATE INDEX ON crm_lead_contact_events (tenant_id, lead_id, contacted_on DESC, id DESC);

CREATE TABLE variant_option_values (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    variant_id int NOT NULL,
    product_option_id int NOT NULL,
    product_option_value_id int NOT NULL,
    FOREIGN KEY (product_option_id, product_id, tenant_id) REFERENCES product_options(id, product_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (product_option_value_id, product_option_id, product_id, tenant_id) REFERENCES product_option_values(id, product_option_id, product_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, variant_id, product_option_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (variant_id, product_id, tenant_id) REFERENCES product_variants(id, product_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON variant_option_values (tenant_id, product_id, variant_id, product_option_id);

CREATE INDEX ON variant_option_values (tenant_id, product_option_value_id, variant_id);

CREATE INDEX ON variant_option_values (variant_id);

CREATE TABLE voucher_reception_documents (
    id int GENERATED BY DEFAULT AS IDENTITY,
    attachment_id int NOT NULL,
    received_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sender_email varchar(255) CHECK (sender_email IS NULL OR sender_email = lower(sender_email)),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    subject varchar(100),
    type varchar(30) DEFAULT 'SUPPLIER_INVOICE' NOT NULL,
    supplier_invoice_auto_register_evaluated_at timestamptz,
    supplier_invoice_auto_register_outcome varchar(20) CHECK (supplier_invoice_auto_register_outcome IS NULL OR supplier_invoice_auto_register_outcome IN ('SKIPPED', 'FAILED')),
    supplier_invoice_auto_register_reason varchar(50) CONSTRAINT voucher_reception_documents_supplier_invoice_auto_regist_check1 CHECK (supplier_invoice_auto_register_reason IS NULL OR supplier_invoice_auto_register_reason IN ('AUTOMATION_NOT_ENABLED', 'SUPPLIER_NOT_ELIGIBLE', 'DOCUMENT_NOT_ELIGIBLE', 'AMOUNT_ABOVE_THRESHOLD', 'PAYMENT_SETUP_UNAVAILABLE', 'DUPLICATE_INVOICE', 'PROCESSING_ERROR')),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments,
    CHECK (supplier_invoice_auto_register_evaluated_at IS NULL AND supplier_invoice_auto_register_outcome IS NULL AND supplier_invoice_auto_register_reason IS NULL OR supplier_invoice_auto_register_evaluated_at IS NOT NULL AND supplier_invoice_auto_register_outcome IS NOT NULL AND supplier_invoice_auto_register_reason IS NOT NULL AND (supplier_invoice_auto_register_outcome = 'FAILED' AND supplier_invoice_auto_register_reason = 'PROCESSING_ERROR' OR supplier_invoice_auto_register_outcome = 'SKIPPED' AND supplier_invoice_auto_register_reason <> 'PROCESSING_ERROR'))
);

CREATE INDEX ON voucher_reception_documents (attachment_id);

CREATE INDEX ON voucher_reception_documents (tenant_id);

CREATE TABLE income_reception_analysis_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    voucher_reception_document_id int NOT NULL,
    line_number smallint NOT NULL,
    account_side varchar(10) NOT NULL,
    account_number varchar(20) NOT NULL,
    amount_incl_vat numeric(15,2) NOT NULL CHECK (amount_incl_vat > 0),
    vat_code varchar(50) NOT NULL,
    description text NOT NULL,
    FOREIGN KEY (voucher_reception_document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE CASCADE,
    UNIQUE (voucher_reception_document_id, line_number)
);

CREATE INDEX ON income_reception_analysis_lines (tenant_id);

CREATE TABLE invoice_reception_notifications (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    document_id int,
    received_document_id int NOT NULL,
    receipt_key char(64) NOT NULL,
    settings_version int NOT NULL,
    received_at timestamptz DEFAULT now() NOT NULL,
    status varchar(20) DEFAULT 'pending' NOT NULL,
    suppression_reason varchar(40),
    recipient varchar(254),
    outbox_id int,
    UNIQUE (tenant_id, received_document_id),
    FOREIGN KEY (document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE SET NULL (document_id),
    FOREIGN KEY (outbox_id, tenant_id) REFERENCES email_outbox(id, sender_tenant_id),
    UNIQUE (tenant_id, receipt_key)
);

CREATE INDEX ON invoice_reception_notifications (outbox_id) WHERE outbox_id IS NOT NULL;

CREATE INDEX ON invoice_reception_notifications (received_at, tenant_id) WHERE status = 'pending';

CREATE TABLE supplier_invoice (
    reversed_at timestamptz,
    disputed_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    date date NOT NULL,
    due_date date,
    voucher_reception_document_id int,
    supplier_id int NOT NULL,
    payment_prediction_used bool DEFAULT false NOT NULL,
    auto_register_payment_creation_failed bool DEFAULT false NOT NULL,
    created_by_automation bool DEFAULT false NOT NULL,
    number varchar(100) NOT NULL CHECK (btrim(number) <> ''),
    currency char(3) NOT NULL,
    total_amount numeric(15,2),
    document_type varchar(20) DEFAULT 'INVOICE' NOT NULL,
    kid_number varchar(40),
    auto_register_payment_threshold_used numeric(19,2) CHECK (auto_register_payment_threshold_used IS NULL OR auto_register_payment_threshold_used > 0),
    pension_handled_in_previous_system bool DEFAULT false NOT NULL,
    prediction_justification text,
    PRIMARY KEY (id, tenant_id),
    CHECK (created_by_automation = (auto_register_payment_threshold_used IS NOT NULL)),
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers,
    UNIQUE (tenant_id, supplier_id, number),
    FOREIGN KEY (voucher_reception_document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE SET NULL (voucher_reception_document_id)
);

CREATE INDEX ON supplier_invoice (disputed_at) WHERE disputed_at IS NOT NULL;

CREATE INDEX ON supplier_invoice (document_type) WHERE document_type = 'CREDIT_NOTE';

CREATE INDEX ON supplier_invoice (supplier_id);

CREATE INDEX ON supplier_invoice (tenant_id, supplier_id, date DESC, id DESC) WHERE reversed_at IS NULL;

CREATE INDEX ON supplier_invoice (voucher_reception_document_id);

CREATE TABLE supplier_invoice_ai_analysis (
    supplier_invoice_id int PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    date date NOT NULL,
    due_date date,
    vat_percentage int,
    accrual_period_count int,
    voucher_type varchar(50) NOT NULL,
    number varchar(255) NOT NULL,
    kid_number varchar(255),
    account_number varchar(255),
    swift_bic varchar(255),
    company_name varchar(255) NOT NULL,
    company_number varchar(255),
    order_total numeric(15,2) NOT NULL,
    currency varchar(10) NOT NULL,
    vat_code varchar(50),
    vat_amount numeric(15,2),
    description text,
    predicted_supplier_id varchar(255),
    document_type varchar(50) NOT NULL,
    total_amount_with_25_percent_vat numeric(15,2),
    total_amount_with_15_percent_vat numeric(15,2),
    total_amount_with_12_percent_vat numeric(15,2),
    total_amount_with_0_percent_vat numeric(15,2),
    debit_account varchar(255) NOT NULL,
    debit_vat_code varchar(50),
    accrual_period_start varchar(20),
    accrual_period_end varchar(20),
    cost_predictions jsonb,
    prediction_review_required bool DEFAULT false NOT NULL,
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE CASCADE
);

CREATE INDEX ON supplier_invoice_ai_analysis (tenant_id);

CREATE TABLE supplier_invoice_attachments (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    supplier_invoice_id int NOT NULL,
    attachment_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON supplier_invoice_attachments (supplier_invoice_id, attachment_id);

CREATE INDEX ON supplier_invoice_attachments (tenant_id);

CREATE TABLE supplier_invoice_cost_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    supplier_invoice_id int NOT NULL,
    accrual_period date,
    row_number smallint NOT NULL,
    accrual_period_count smallint DEFAULT 1 NOT NULL,
    accrual_enabled bool DEFAULT false NOT NULL,
    accrual_account_number varchar(20),
    CHECK (accrual_enabled OR accrual_period IS NULL AND accrual_period_count = 1 AND accrual_account_number IS NULL),
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON supplier_invoice_cost_lines (supplier_invoice_id, row_number);

CREATE INDEX ON supplier_invoice_cost_lines (tenant_id);

CREATE TABLE supplier_invoice_payment_prediction (
    supplier_invoice_id int PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    beneficiary_name varchar(255) NOT NULL,
    account_number varchar(255),
    iban varchar(255) CHECK (iban IS NULL OR length(btrim(iban)) > 0),
    swift_bic varchar(255),
    bank_name varchar(255),
    bank_country_code varchar(10),
    routing_number varchar(255),
    routing_type varchar(255),
    bank_account_category varchar(255),
    local_clearing_system varchar(255),
    reference varchar(255),
    reason text,
    beneficiary_street_address varchar(255),
    beneficiary_city varchar(255),
    beneficiary_state varchar(255),
    beneficiary_postcode varchar(255),
    beneficiary_country_code varchar(10),
    bank_street_address varchar(255),
    bank_city varchar(255),
    bank_state varchar(255),
    bank_postcode varchar(255),
    bank_address_country_code varchar(10),
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE CASCADE
);

CREATE INDEX ON supplier_invoice_payment_prediction (tenant_id);

CREATE TABLE voucher_reception_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    voucher_reception_document_id int NOT NULL,
    attachment_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments,
    FOREIGN KEY (voucher_reception_document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE CASCADE
);

CREATE TABLE voucher_reception_document_manual_voucher_analysis (
    voucher_reception_document_id int PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    voucher_date date NOT NULL,
    voucher_description text NOT NULL,
    voucher_amount numeric(15,2),
    voucher_currency varchar(10) NOT NULL,
    debit_account varchar(255),
    credit_account varchar(255),
    posting_amount numeric(15,2),
    debit_vat_code varchar(50),
    credit_vat_code varchar(50),
    FOREIGN KEY (voucher_reception_document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE CASCADE
);

CREATE INDEX ON voucher_reception_document_manual_voucher_analysis (tenant_id);

CREATE TABLE voucher_sequences (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    next_number int NOT NULL,
    year smallint NOT NULL,
    voucher_type varchar(30) NOT NULL,
    PRIMARY KEY (tenant_id, year, voucher_type)
);

CREATE TABLE warehouse_settings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    locations_enabled bool DEFAULT false NOT NULL,
    automatic_inventory_adjustment bool DEFAULT false NOT NULL
);

CREATE TABLE withholding_taxes (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tax_card_id int NOT NULL,
    deduction_code varchar(255),
    free_card_amount numeric(15,2),
    table_number varchar(255),
    percentage_rate numeric(8,2),
    months_for_deduction numeric(5,2),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (tax_card_id, tenant_id) REFERENCES tax_cards ON DELETE CASCADE
);

CREATE INDEX ON withholding_taxes (tenant_id, tax_card_id);

CREATE TABLE woocommerce_store_config (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    store_host text NOT NULL,
    base_url text NOT NULL,
    consumer_key text NOT NULL,
    consumer_secret text NOT NULL,
    order_sync_start_date date,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sync_product_images_enabled bool DEFAULT false NOT NULL,
    checkout_accounting_start_date date,
    checkout_payment_methods jsonb DEFAULT '{}' NOT NULL,
    sync_processing_orders_enabled bool DEFAULT false NOT NULL,
    kustom_customer_advances bool DEFAULT false NOT NULL,
    kustom_history_reviewed bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

CREATE UNIQUE INDEX ON woocommerce_store_config (tenant_id, store_host);

CREATE TABLE woocommerce_product_sync (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    store_host text NOT NULL,
    woocommerce_product_id int NOT NULL,
    reai_product_id int NOT NULL,
    synced_at timestamptz NOT NULL,
    FOREIGN KEY (reai_product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, store_host) REFERENCES woocommerce_store_config(tenant_id, store_host) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON woocommerce_product_sync (tenant_id, store_host, woocommerce_product_id);

CREATE TABLE woocommerce_product_variant_sync (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    store_host text NOT NULL,
    woocommerce_product_id int NOT NULL,
    woocommerce_variation_id int NOT NULL,
    reai_variant_id int NOT NULL,
    synced_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, store_host, woocommerce_product_id) REFERENCES woocommerce_product_sync(tenant_id, store_host, woocommerce_product_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT woocommerce_product_variant_s_tenant_id_store_host_woocomm_key1 UNIQUE (tenant_id, store_host, woocommerce_product_id, reai_variant_id),
    UNIQUE (tenant_id, store_host, woocommerce_product_id, woocommerce_variation_id),
    FOREIGN KEY (reai_variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE
);

CREATE TABLE work_schedule_employee_order (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    sort_order int NOT NULL,
    PRIMARY KEY (tenant_id, employee_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE
);

CREATE TABLE work_schedule_groups (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(40) NOT NULL CHECK (length(btrim(name)) > 0),
    UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ON work_schedule_groups (tenant_id, lower(name));

CREATE TABLE employee_work_days (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    work_date date NOT NULL,
    start_time time(0) without time zone,
    comment varchar(1000),
    group_id int,
    end_time time,
    holiday bool DEFAULT false NOT NULL,
    CHECK (NOT holiday OR group_id IS NULL AND start_time IS NULL AND end_time IS NULL),
    CONSTRAINT employee_work_days_check1 CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE,
    FOREIGN KEY (group_id, tenant_id) REFERENCES work_schedule_groups(id, tenant_id),
    UNIQUE (tenant_id, employee_id, work_date)
);

CREATE INDEX ON employee_work_days (tenant_id, work_date);

CREATE TABLE work_schedule_notifications (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    week_start date NOT NULL CHECK (EXTRACT(isodow FROM week_start) = 1),
    snapshot jsonb NOT NULL,
    notified_email varchar(254) NOT NULL,
    notified_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, employee_id, week_start),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees
);

CREATE TABLE work_schedule_open_shifts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    work_date date NOT NULL,
    group_id int,
    start_time time CHECK (start_time IS NULL OR EXTRACT(second FROM start_time) = 0),
    end_time time CHECK (end_time IS NULL OR EXTRACT(second FROM end_time) = 0),
    comment varchar(1000),
    published_at timestamptz,
    publisher_email varchar(254),
    claimed_employee_id int,
    CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time),
    FOREIGN KEY (claimed_employee_id, tenant_id) REFERENCES employees,
    FOREIGN KEY (group_id, tenant_id) REFERENCES work_schedule_groups(id, tenant_id)
);

CREATE INDEX ON work_schedule_open_shifts (tenant_id, work_date) WHERE claimed_employee_id IS NULL;

CREATE TABLE work_schedule_staff_exclusions (
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    PRIMARY KEY (tenant_id, employee_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees
);

CREATE TABLE ztl_payment_config (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE UNIQUE,
    onboarding_status varchar(50) NOT NULL,
    onboarding_url varchar(500),
    onboarding_flow_id varchar(255),
    active bool DEFAULT false NOT NULL,
    onboarding_version smallint DEFAULT 1 NOT NULL,
    onboarding_country_code varchar(2),
    kyc_completion_email_sent bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

COMMENT ON COLUMN ztl_payment_config.onboarding_version IS 'ZTL onboarding API version: 1 for Norway, 2 for Sweden/Denmark';

COMMENT ON COLUMN ztl_payment_config.onboarding_country_code IS 'Country code used for onboarding (NO, SE, DK)';

CREATE TABLE ztl_user_consent (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
    bank_bic varchar(11) NOT NULL,
    bank_identifier varchar(255) NOT NULL,
    consent_id varchar(36),
    consent_authorization_id varchar(255) NOT NULL UNIQUE,
    consent_reference varchar(255) NOT NULL,
    authorization_type varchar(50),
    status varchar(50) NOT NULL,
    is_active bool DEFAULT false NOT NULL,
    expiration_date date,
    sca_url varchar(1000),
    sca_url_expires_at timestamptz,
    created_in_tenant_id int REFERENCES tenants ON DELETE SET NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    usage varchar(8) DEFAULT 'BUSINESS' NOT NULL
);

CREATE INDEX ON ztl_user_consent (consent_id) WHERE consent_id IS NOT NULL;

CREATE UNIQUE INDEX ON ztl_user_consent (user_id, bank_bic, bank_identifier, created_in_tenant_id, usage) NULLS NOT DISTINCT WHERE is_active;

CREATE INDEX ON ztl_user_consent (user_id) WHERE is_active;

CREATE TABLE ztl_consent (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    user_id int REFERENCES users ON DELETE CASCADE,
    ztl_config_id int NOT NULL,
    consent_authorization_id varchar(255) NOT NULL UNIQUE,
    consent_reference varchar(255) NOT NULL,
    consent_id varchar(36),
    bank_bic varchar(11) NOT NULL,
    bank_identifier varchar(255),
    authorization_type varchar(50),
    status varchar(50) NOT NULL,
    expiration_date date,
    sca_url varchar(1000),
    sca_url_expires_at timestamptz,
    is_active bool DEFAULT false NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    consent_expiry_reminder_sent_for_date date,
    no_psd2_payment_accounts_guidance_email_sent_at timestamptz,
    receive_approval_reminders bool DEFAULT true NOT NULL,
    payment_approval_reminder_sent_date date,
    user_consent_id int REFERENCES ztl_user_consent,
    receive_failed_payment_notifications bool DEFAULT true NOT NULL,
    usage varchar(8) DEFAULT 'BUSINESS' NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (ztl_config_id, tenant_id) REFERENCES ztl_payment_config ON DELETE CASCADE
);

CREATE INDEX ON ztl_consent (bank_bic, bank_identifier, is_active) WHERE is_active = true AND bank_identifier IS NOT NULL;

CREATE INDEX ON ztl_consent (consent_id) WHERE consent_id IS NOT NULL;

CREATE INDEX ON ztl_consent (status) WHERE status = 'ACCEPTED';

CREATE UNIQUE INDEX ON ztl_consent (tenant_id, bank_bic, user_id, bank_identifier, usage) WHERE is_active AND bank_identifier IS NOT NULL;

CREATE INDEX ON ztl_consent (tenant_id, status, sca_url_expires_at) WHERE status = 'PENDING' AND sca_url IS NOT NULL;

CREATE INDEX ON ztl_consent (user_consent_id);

CREATE INDEX ON ztl_consent (user_id) WHERE is_active = true;

CREATE INDEX ON ztl_consent (ztl_config_id);

CREATE TABLE ztl_consent_account_link (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    company_bank_id int NOT NULL,
    consent_id int NOT NULL,
    ztl_account_id varchar(255) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
    linked_at timestamptz NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    UNIQUE (company_bank_id, consent_id),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank ON DELETE CASCADE,
    FOREIGN KEY (consent_id, tenant_id) REFERENCES ztl_consent ON DELETE CASCADE
);

CREATE INDEX ON ztl_consent_account_link (tenant_id, company_bank_id);

CREATE INDEX ON ztl_consent_account_link (tenant_id, consent_id);

CREATE INDEX ON ztl_consent_account_link (user_id);

CREATE TABLE ztl_user_consent_account (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_consent_id int NOT NULL REFERENCES ztl_user_consent ON DELETE CASCADE,
    ztl_account_id varchar(255) NOT NULL,
    iban varchar(34),
    bban varchar(34),
    currency varchar(3),
    name varchar(255),
    owner_name varchar(255),
    bic varchar(20),
    type varchar(50),
    country varchar(3),
    captured_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (user_consent_id, ztl_account_id)
);

CREATE TABLE vouchers (
    id bigint GENERATED BY DEFAULT AS IDENTITY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    reversed_at timestamptz,
    date date NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    vat_submission_id int,
    number smallint NOT NULL,
    description text,
    voucher_type varchar(30) DEFAULT 'MANUAL' NOT NULL,
    suggested_income_bank_fee numeric(10,2),
    review_reason text,
    review_status varchar(30) DEFAULT 'NONE' NOT NULL,
    vat_basis_corrections jsonb,
    PRIMARY KEY (id, tenant_id)
);

CREATE INDEX ON vouchers (date);

CREATE INDEX ON vouchers (number);

CREATE INDEX ON vouchers (reversed_at) WHERE reversed_at IS NOT NULL;

CREATE INDEX ON vouchers (tenant_id, date DESC, number DESC, id DESC);

CREATE UNIQUE INDEX ON vouchers (tenant_id, EXTRACT(year FROM date)) WHERE voucher_type = 'ANNUAL_RESULT_DISPOSAL' AND reversed_at IS NULL;

CREATE UNIQUE INDEX ON vouchers (tenant_id) WHERE voucher_type = 'OPENING_BALANCE' AND reversed_at IS NULL;

CREATE INDEX ON vouchers (tenant_id, review_status, date, id) WHERE review_status <> 'NONE';

CREATE UNIQUE INDEX ON vouchers (tenant_id, voucher_type, number, EXTRACT(year FROM date));

CREATE INDEX ON vouchers (vat_submission_id);

CREATE CONSTRAINT TRIGGER vouchers_require_postings AFTER INSERT OR UPDATE ON vouchers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ensure_voucher_has_postings();

CREATE TABLE a_melding (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    organization_number varchar(9) NOT NULL,
    reporting_period date NOT NULL,
    status varchar(50) NOT NULL,
    validation_errors text,
    created_at date NOT NULL,
    submitted_at date,
    correction_of int,
    is_correction bool DEFAULT false NOT NULL,
    message_id varchar(100),
    dialog_id varchar(100),
    replaces_message_id varchar(100),
    payment_kid_eni varchar(25),
    payment_kid_income_tax varchar(25),
    payment_account_number varchar(11),
    income_tax_amount int,
    employer_tax_amount int,
    financial_tax_amount int,
    expense_deduction_amount int,
    payment_due_date date,
    pension_source_voucher_ids text,
    selected_employee_ids int[],
    clear_reported_income bool DEFAULT false NOT NULL,
    nav_refund_amount numeric(15,2) CHECK (nav_refund_amount >= 0),
    nav_refund_voucher_id bigint,
    pension_settlements jsonb,
    report_number int NOT NULL CHECK (report_number > 0),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (nav_refund_voucher_id, tenant_id) REFERENCES vouchers,
    UNIQUE (tenant_id, report_number)
);

CREATE TRIGGER a_melding_assign_report_number BEFORE INSERT ON a_melding FOR EACH ROW EXECUTE FUNCTION assign_a_melding_report_number();

CREATE TRIGGER a_melding_preserve_report_number BEFORE UPDATE OF report_number, tenant_id ON a_melding FOR EACH ROW EXECUTE FUNCTION preserve_a_melding_report_number();

CREATE TABLE a_melding_line (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    a_melding_id int NOT NULL,
    employee_id int,
    national_id varchar(11) NOT NULL CHECK (national_id ~ '^[0-9]{11}$'),
    employment_start_date date NOT NULL,
    employment_end_date date,
    employment_type varchar(50) NOT NULL,
    position_code varchar(10) CHECK (position_code IS NULL OR position_code ~ '^[0-9]{7}$'),
    gross_salary numeric(15,2) NOT NULL,
    tax_deducted numeric(15,2) NOT NULL CHECK (tax_deducted >= 0),
    employer_contribution numeric(15,2),
    holiday_pay numeric(15,2),
    pension_contribution numeric(15,2),
    other_benefits numeric(15,2),
    hours_worked numeric(10,2) CHECK (hours_worked IS NULL OR hours_worked >= 0 AND hours_worked <= 168),
    percentage_employment numeric(5,2) CHECK (percentage_employment >= 0 AND percentage_employment <= 100),
    income_description varchar(200),
    form_of_employment varchar(50),
    working_hours_scheme varchar(50),
    last_salary_change_date date,
    employee_salary_payment_ids text,
    pension_provider_org_number varchar(9),
    name varchar(100),
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    last_position_percentage_change_date date,
    mileage_allowance numeric(15,2) DEFAULT 0 NOT NULL,
    mileage_kilometers numeric(15,2) DEFAULT 0 NOT NULL,
    travel_meals_day_amount numeric(15,2) DEFAULT 0 NOT NULL,
    travel_meals_day_days smallint DEFAULT 0 NOT NULL,
    travel_meals_hotel_amount numeric(15,2) DEFAULT 0 NOT NULL,
    travel_meals_hotel_days smallint DEFAULT 0 NOT NULL,
    travel_meals_no_kitchen_amount numeric(15,2) DEFAULT 0 NOT NULL,
    travel_meals_no_kitchen_days smallint DEFAULT 0 NOT NULL,
    travel_meals_with_kitchen_amount numeric(15,2) DEFAULT 0 NOT NULL,
    travel_meals_with_kitchen_days smallint DEFAULT 0 NOT NULL,
    electronic_communication_benefit numeric(15,2) DEFAULT 0 NOT NULL,
    taxable_insurance_benefit numeric(15,2) DEFAULT 0 NOT NULL,
    employment_end_reason varchar(32),
    mileage_passenger_amount numeric(15,2) DEFAULT 0 NOT NULL,
    mileage_passenger_kilometers numeric(15,2) DEFAULT 0 NOT NULL,
    mileage_equipment_amount numeric(15,2) DEFAULT 0 NOT NULL,
    mileage_equipment_kilometers numeric(15,2) DEFAULT 0 NOT NULL,
    FOREIGN KEY (a_melding_id, tenant_id) REFERENCES a_melding ON DELETE CASCADE,
    CHECK (employment_end_date IS NULL OR employment_end_date >= employment_start_date),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE SET NULL (employee_id)
);

CREATE INDEX ON a_melding_line (employee_id);

CREATE INDEX ON a_melding_line (tenant_id);

CREATE TABLE adyen_liable_monthly_vouchers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_liable_account_id int NOT NULL,
    year smallint NOT NULL CHECK (year >= 2000),
    month smallint NOT NULL CHECK (month >= 1 AND month <= 12),
    voucher_id bigint NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (adyen_liable_account_id, tenant_id) REFERENCES adyen_liable_accounts(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, year, month),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers,
    UNIQUE (voucher_id, tenant_id)
);

CREATE TABLE adyen_transactions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int REFERENCES tenants ON DELETE CASCADE,
    adyen_balance_account_id int,
    transaction_id text NOT NULL UNIQUE,
    balance_platform_id text NOT NULL,
    account_holder_id text,
    balance_account_id text NOT NULL,
    provider_status text NOT NULL,
    amount_value bigint NOT NULL,
    amount_currency char(3) NOT NULL CHECK (amount_currency ~ '^[A-Z]{3}$'),
    booking_date timestamptz NOT NULL,
    value_date timestamptz,
    creation_date timestamptz,
    transfer_id text,
    transfer_reference text,
    description text,
    reference_for_beneficiary text,
    category_type text,
    platform_payment_type text,
    payment_psp_reference text,
    modification_psp_reference text,
    payment_merchant_reference text,
    modification_merchant_reference text,
    category_data jsonb,
    projection_status varchar(10) NOT NULL,
    blocked_reason varchar(50),
    event_timestamp timestamptz,
    received_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    accounting_status varchar(20) DEFAULT 'PENDING' NOT NULL,
    accounting_attempt_count smallint DEFAULT 0 NOT NULL CHECK (accounting_attempt_count >= 0),
    accounting_next_attempt_at timestamptz DEFAULT now() NOT NULL,
    accounting_failure_reason text,
    accounting_voucher_id bigint,
    accounted_at timestamptz,
    FOREIGN KEY (accounting_voucher_id, tenant_id) REFERENCES vouchers,
    FOREIGN KEY (adyen_balance_account_id, tenant_id) REFERENCES adyen_balance_accounts(id, tenant_id),
    CHECK (projection_status = 'MAPPED' AND blocked_reason IS NULL AND tenant_id IS NOT NULL AND adyen_balance_account_id IS NOT NULL AND provider_status = 'BOOKED' AND amount_currency = 'NOK' OR projection_status = 'BLOCKED' AND blocked_reason IS NOT NULL),
    UNIQUE (id, tenant_id)
);

CREATE INDEX ON adyen_transactions (accounting_next_attempt_at, booking_date, id) WHERE projection_status = 'MAPPED' AND accounting_status IN ('PENDING', 'FAILED');

CREATE INDEX ON adyen_transactions (balance_account_id, amount_currency);

CREATE INDEX ON adyen_transactions (booking_date, blocked_reason) WHERE projection_status = 'BLOCKED';

CREATE INDEX ON adyen_transactions (modification_psp_reference) WHERE modification_psp_reference IS NOT NULL;

CREATE INDEX ON adyen_transactions (payment_psp_reference) WHERE payment_psp_reference IS NOT NULL;

CREATE INDEX ON adyen_transactions (tenant_id, booking_date);

CREATE INDEX ON adyen_transactions (transfer_id) WHERE transfer_id IS NOT NULL;

CREATE TABLE adyen_liable_transaction_accounting (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    adyen_liable_account_id int NOT NULL,
    adyen_transaction_id int NOT NULL REFERENCES adyen_transactions UNIQUE,
    accounting_status varchar(20) DEFAULT 'PENDING' NOT NULL CHECK (accounting_status IN ('PENDING', 'FAILED', 'ACCOUNTED', 'NEEDS_CLASSIFICATION')),
    accounting_attempt_count smallint DEFAULT 0 NOT NULL CHECK (accounting_attempt_count >= 0),
    accounting_next_attempt_at timestamptz DEFAULT now() NOT NULL,
    accounting_failure_reason text,
    accounting_voucher_id bigint,
    accounted_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (accounting_voucher_id, tenant_id) REFERENCES vouchers,
    FOREIGN KEY (adyen_liable_account_id, tenant_id) REFERENCES adyen_liable_accounts(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (id, tenant_id)
);

CREATE INDEX ON adyen_liable_transaction_accounting (accounting_next_attempt_at, id) WHERE accounting_status IN ('PENDING', 'FAILED');

CREATE INDEX ON adyen_liable_transaction_accounting (accounting_voucher_id) WHERE accounting_voucher_id IS NOT NULL;

CREATE TABLE assets (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    description text,
    account_number varchar(30) NOT NULL CHECK (account_number ~ '^[0-9]+$'),
    currency_code varchar(3) NOT NULL,
    issue_date date NOT NULL,
    amount numeric(15,2) NOT NULL,
    useful_life_in_months int DEFAULT 36 NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamptz,
    currency_amount numeric(15,2),
    voucher_id bigint,
    depreciation_method varchar(20) DEFAULT 'MANUAL' NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE INDEX ON assets (tenant_id);

CREATE UNIQUE INDEX ON assets (voucher_id) WHERE voucher_id IS NOT NULL;

CREATE TABLE bank_reconciliations (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    company_bank_id int NOT NULL,
    reconciliation_month date NOT NULL,
    voucher_id bigint,
    ending_balance numeric(19,2),
    statement_voucher_id bigint,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (statement_voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (statement_voucher_id),
    UNIQUE (tenant_id, company_bank_id, reconciliation_month),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON bank_reconciliations (reconciliation_month);

CREATE INDEX ON bank_reconciliations (statement_voucher_id) WHERE statement_voucher_id IS NOT NULL;

CREATE TABLE bank_reconciliation_rule_vouchers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    bank_reconciliation_id int NOT NULL,
    reconciliation_rule_id int NOT NULL,
    voucher_id bigint NOT NULL,
    FOREIGN KEY (bank_reconciliation_id, tenant_id) REFERENCES bank_reconciliations ON DELETE CASCADE,
    FOREIGN KEY (reconciliation_rule_id, tenant_id) REFERENCES reconciliation_rules ON DELETE CASCADE,
    UNIQUE (tenant_id, bank_reconciliation_id, reconciliation_rule_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE CASCADE
);

CREATE INDEX ON bank_reconciliation_rule_vouchers (bank_reconciliation_id);

CREATE TABLE customs_declarations (
    id int GENERATED BY DEFAULT AS IDENTITY,
    voucher_id bigint,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    basis_amount numeric(18,2),
    vat_code varchar(4),
    cost_account varchar(32),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    number int NOT NULL,
    declaration_month date NOT NULL CHECK (declaration_month = date_trunc('month', declaration_month)::date),
    description text,
    from_altinn bool DEFAULT false NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE UNIQUE INDEX ON customs_declarations (tenant_id, declaration_month);

CREATE UNIQUE INDEX ON customs_declarations (tenant_id, number, EXTRACT(year FROM declaration_month));

CREATE TABLE customs_declaration_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    customs_declaration_id int NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (customs_declaration_id, tenant_id) REFERENCES customs_declarations,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE
);

CREATE INDEX ON customs_declaration_attachments (customs_declaration_id);

CREATE INDEX ON customs_declaration_attachments (tenant_id);

CREATE TABLE expenses (
    id int GENERATED BY DEFAULT AS IDENTITY,
    number smallint NOT NULL,
    title varchar(255),
    status varchar(50) NOT NULL,
    employee_id int,
    voucher_id bigint,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_travel bool DEFAULT false NOT NULL,
    project_id int,
    included_in_payslip bool DEFAULT false NOT NULL,
    purpose varchar(255),
    reversed_at timestamptz,
    import_source varchar(100),
    import_source_instance varchar(200),
    import_external_id varchar(255),
    import_fingerprint varchar(64),
    import_payload jsonb,
    settled_in_previous_system bool DEFAULT false NOT NULL,
    external_settlement_date date,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE CASCADE,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON expenses (employee_id);

CREATE INDEX ON expenses (id) WHERE reversed_at IS NULL;

CREATE UNIQUE INDEX ON expenses (tenant_id, import_source, import_source_instance, import_external_id) WHERE import_external_id IS NOT NULL;

CREATE TABLE expense_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    expense_id int NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    FOREIGN KEY (expense_id, tenant_id) REFERENCES expenses ON DELETE CASCADE
);

CREATE INDEX ON expense_attachments (expense_id);

CREATE INDEX ON expense_attachments (tenant_id);

CREATE TABLE expense_costs (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    description varchar(255),
    date date NOT NULL,
    amount numeric(15,2) NOT NULL,
    currency char(3) NOT NULL,
    category varchar(50),
    vat_code varchar(10),
    expense_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    row_number smallint DEFAULT 0 NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    currency_amount numeric(15,2),
    amount_overridden bool DEFAULT false NOT NULL,
    FOREIGN KEY (expense_id, tenant_id) REFERENCES expenses ON DELETE CASCADE
);

CREATE INDEX ON expense_costs (expense_id);

CREATE TABLE expense_mileage_allowances (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    expense_id int NOT NULL,
    date date NOT NULL,
    purpose text,
    company_car bool DEFAULT false NOT NULL,
    kilometers numeric(12,2) NOT NULL,
    kilometer_rate numeric(12,2) NOT NULL,
    amount numeric(12,2) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    from_location varchar(255),
    to_location varchar(255),
    road_toll_amount numeric(12,2) DEFAULT 0 NOT NULL CHECK (road_toll_amount >= 0),
    FOREIGN KEY (expense_id, tenant_id) REFERENCES expenses ON DELETE CASCADE
);

CREATE INDEX ON expense_mileage_allowances (expense_id);

CREATE TABLE expense_per_diem_compensations (
    id int GENERATED BY DEFAULT AS IDENTITY,
    expense_id int NOT NULL,
    country_code char(2) NOT NULL,
    address varchar(255),
    amount numeric(15,2) NOT NULL,
    date_from date NOT NULL,
    date_to date NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    trip_type varchar(32) DEFAULT 'WITH_OVERNIGHT_STAY' NOT NULL,
    lodging_type varchar(40),
    time_from time,
    time_to time,
    breakfast_count smallint DEFAULT 0 NOT NULL,
    lunch_count smallint DEFAULT 0 NOT NULL,
    dinner_count smallint DEFAULT 0 NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (expense_id, tenant_id) REFERENCES expenses ON DELETE CASCADE
);

CREATE TABLE inventory_adjustment_months (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    month date NOT NULL CHECK (EXTRACT(day FROM month) = 1),
    voucher_id bigint,
    attachment_id int,
    adjusted_at timestamptz DEFAULT now() NOT NULL,
    inventory_value numeric(18,2) DEFAULT 0 NOT NULL,
    adjustment_amount numeric(18,2) DEFAULT 0 NOT NULL,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE SET NULL (attachment_id),
    UNIQUE (tenant_id, month),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE TABLE invoices_ar (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    number varchar(100) NOT NULL,
    issue_date date NOT NULL,
    due_date date,
    company_bank_id int,
    voucher_id bigint,
    comment text,
    reference varchar(100) NOT NULL,
    created_from_invoice_id int,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    our_reference varchar(255),
    buyer_reference varchar(255),
    project_id int,
    delivery_date date,
    delivery_address_id int,
    import_customer_id int,
    import_currency_code varchar(3),
    import_invoice_email varchar(255),
    original_attachment_id int,
    payment_reference varchar(100),
    import_source varchar(100),
    import_source_instance varchar(255),
    external_id varchar(255),
    import_fingerprint varchar(64),
    import_total_amount numeric(14,2),
    import_vat_amount numeric(14,2),
    import_company_currency_amount numeric(14,2),
    automatic_reminders_enabled bool DEFAULT true NOT NULL,
    purchase_order_reference varchar(100),
    import_payable_rounding_amount numeric(14,2),
    seller_name_snapshot varchar(255),
    customer_name_snapshot varchar(255),
    seller_employee_id int,
    seller_employee_id_snapshot int,
    seller_employee_name_snapshot text,
    return_credit_note bool DEFAULT false NOT NULL,
    reply_to_email varchar(254),
    PRIMARY KEY (id, tenant_id),
    CHECK (created_from_invoice_id IS NULL OR created_from_invoice_id <> id),
    CONSTRAINT invoices_ar_check1 CHECK (import_source IS NULL AND import_source_instance IS NULL AND external_id IS NULL AND import_fingerprint IS NULL AND import_total_amount IS NULL AND import_vat_amount IS NULL AND import_company_currency_amount IS NULL AND voucher_id IS NOT NULL OR import_source IS NOT NULL AND length(TRIM(BOTH FROM import_source)) > 0 AND import_source_instance IS NOT NULL AND length(TRIM(BOTH FROM import_source_instance)) > 0 AND external_id IS NOT NULL AND length(TRIM(BOTH FROM external_id)) > 0 AND import_customer_id IS NOT NULL AND import_currency_code IS NOT NULL AND import_fingerprint IS NOT NULL AND import_total_amount IS NOT NULL AND import_total_amount <> 0 AND import_vat_amount IS NOT NULL AND (voucher_id IS NOT NULL OR import_company_currency_amount IS NOT NULL)),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (created_from_invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (delivery_address_id, tenant_id) REFERENCES addresses,
    FOREIGN KEY (import_customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (original_attachment_id, tenant_id) REFERENCES attachments,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (seller_employee_id, tenant_id) REFERENCES employees ON DELETE SET NULL (seller_employee_id),
    UNIQUE (tenant_id, number),
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, voucher_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE RESTRICT
);

CREATE INDEX ON invoices_ar (delivery_address_id) WHERE delivery_address_id IS NOT NULL;

CREATE UNIQUE INDEX ON invoices_ar (tenant_id, created_from_invoice_id) WHERE created_from_invoice_id IS NOT NULL AND NOT return_credit_note;

CREATE INDEX invoices_ar_tenant_id_created_from_invoice_id_idx1 ON invoices_ar (tenant_id, created_from_invoice_id) WHERE created_from_invoice_id IS NOT NULL;

CREATE UNIQUE INDEX ON invoices_ar (tenant_id, import_source, import_source_instance, external_id) WHERE import_source IS NOT NULL;

CREATE INDEX ON invoices_ar (tenant_id, updated_at, id);

CREATE CONSTRAINT TRIGGER trg_invoices_ar_source_or_allocation_ck AFTER INSERT ON invoices_ar DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_invoice_ar_source_or_allocation();

CREATE TABLE adyen_refund_reservations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL,
    request_id uuid NOT NULL,
    amount_value bigint NOT NULL CHECK (amount_value > 0),
    kind varchar(20) NOT NULL CHECK (kind IN ('REFUND', 'RETURN_CREDIT')),
    state varchar(20) NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    created_at timestamptz DEFAULT now() NOT NULL,
    reason varchar(500),
    invoice_id int,
    invoice_refund_plan jsonb,
    credit_note_id int,
    CHECK (invoice_id IS NULL AND invoice_refund_plan IS NULL OR invoice_id IS NOT NULL AND invoice_refund_plan IS NOT NULL AND jsonb_typeof(invoice_refund_plan) = 'object' AND invoice_refund_plan ? 'invoiceId' AND (invoice_refund_plan ->> 'invoiceId')::int = invoice_id),
    FOREIGN KEY (credit_note_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (payment_id, tenant_id) REFERENCES adyen_payments(id, tenant_id),
    UNIQUE (tenant_id, request_id)
);

CREATE INDEX ON adyen_refund_reservations (tenant_id, payment_id);

CREATE TABLE efaktura_invoices (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    tenant_config_id int NOT NULL,
    consignment_id varchar(50),
    invoice_number varchar(100) NOT NULL,
    issuer_organization_nr varchar(9) NOT NULL,
    legal_org_number varchar(9) NOT NULL,
    legal_name varchar(255) NOT NULL,
    efaktura_identifier varchar(9),
    document_type varchar(20) DEFAULT 'EFAKTURA' NOT NULL,
    invoice_document_type varchar(30),
    amount numeric(15,2) NOT NULL CHECK (amount >= 0),
    minimum_amount numeric(15,2) CHECK (minimum_amount IS NULL OR minimum_amount >= 0),
    due_date date NOT NULL,
    kid varchar(25),
    account_number varchar(35) NOT NULL,
    status varchar(20) DEFAULT 'SUBMITTED' NOT NULL,
    reject_reason varchar(255),
    message_identifier varchar(50) NOT NULL,
    submitted_at timestamptz DEFAULT now() NOT NULL,
    last_status_check_at timestamptz,
    fallback_allowed bool DEFAULT true NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    UNIQUE (invoice_id, tenant_id),
    UNIQUE (issuer_organization_nr, invoice_number),
    FOREIGN KEY (tenant_config_id, tenant_id) REFERENCES efaktura_tenant_configs
);

CREATE INDEX ON efaktura_invoices (tenant_id, status) WHERE status IN ('SUBMITTED', 'IN_PROGRESS');

CREATE TRIGGER efaktura_invoices_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON efaktura_invoices FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE invoice_ar_credit_note_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    credit_note_invoice_id int NOT NULL,
    allocation_type varchar(40) NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    currency_adjustment_voucher_id bigint,
    allocated_amount numeric(15,2) NOT NULL,
    created_by_posting_group_id int,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (created_by_posting_group_id, tenant_id) REFERENCES posting_groups ON DELETE SET NULL (created_by_posting_group_id),
    FOREIGN KEY (credit_note_invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE,
    FOREIGN KEY (currency_adjustment_voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (currency_adjustment_voucher_id),
    UNIQUE (tenant_id, invoice_id, credit_note_invoice_id),
    CHECK (invoice_id <> credit_note_invoice_id),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE
);

CREATE INDEX ON invoice_ar_credit_note_allocations (tenant_id, created_by_posting_group_id) WHERE created_by_posting_group_id IS NOT NULL;

CREATE UNIQUE INDEX invoice_ar_credit_note_alloca_tenant_id_credit_note_invoic_idx1 ON invoice_ar_credit_note_allocations (tenant_id, credit_note_invoice_id) WHERE allocation_type = 'INVOICE_FULL_REVERSAL';

CREATE INDEX ON invoice_ar_credit_note_allocations (tenant_id, credit_note_invoice_id);

CREATE INDEX ON invoice_ar_credit_note_allocations (tenant_id, currency_adjustment_voucher_id) WHERE currency_adjustment_voucher_id IS NOT NULL;

CREATE UNIQUE INDEX ON invoice_ar_credit_note_allocations (tenant_id, invoice_id) WHERE allocation_type = 'INVOICE_FULL_REVERSAL';

CREATE TRIGGER invoice_ar_credit_note_allocations_touch_invoices AFTER INSERT OR DELETE OR UPDATE ON invoice_ar_credit_note_allocations FOR EACH ROW EXECUTE FUNCTION touch_invoices_from_credit_note_allocation();

CREATE CONSTRAINT TRIGGER trg_invoice_ar_credit_note_alloc_source_ck AFTER INSERT OR DELETE OR UPDATE OF tenant_id, invoice_id, credit_note_invoice_id, allocation_type ON invoice_ar_credit_note_allocations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_invoice_ar_allocation_change();

CREATE TABLE invoice_ar_reminders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    type varchar(30) NOT NULL,
    reminder_date date NOT NULL,
    to_email varchar(100) NOT NULL CHECK (to_email = lower(to_email)),
    reminder_fee_amount numeric(10,2),
    late_interest_amount numeric(10,2),
    created_at timestamptz DEFAULT now() NOT NULL,
    forgiven_at timestamptz,
    due_date date,
    CHECK (due_date >= reminder_date + 14),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE
);

CREATE INDEX ON invoice_ar_reminders (invoice_id);

CREATE TRIGGER invoice_ar_reminders_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON invoice_ar_reminders FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE invoice_ar_rounding_adjustments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    voucher_id bigint NOT NULL,
    adjustment_date date NOT NULL,
    amount numeric(10,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE,
    UNIQUE (tenant_id, voucher_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON invoice_ar_rounding_adjustments (tenant_id, invoice_id);

CREATE TRIGGER invoice_ar_rounding_adjustments_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON invoice_ar_rounding_adjustments FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE invoice_ehf_history (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    error varchar(100),
    created_at date DEFAULT now() NOT NULL,
    sbdh_instance_identifier uuid,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE
);

CREATE INDEX ON invoice_ehf_history (invoice_id);

CREATE INDEX ON invoice_ehf_history (sbdh_instance_identifier) WHERE sbdh_instance_identifier IS NOT NULL;

CREATE TRIGGER invoice_ehf_history_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON invoice_ehf_history FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE invoice_email_history (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    to_email varchar(100) NOT NULL CHECK (to_email = lower(to_email)),
    created_at date DEFAULT now() NOT NULL,
    detail varchar(40) DEFAULT '' NOT NULL,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE
);

CREATE INDEX ON invoice_email_history (invoice_id);

CREATE TRIGGER invoice_email_history_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON invoice_email_history FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE kravia_collection_cases (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    creditor_id int,
    external_invoice_id int,
    status varchar(30) NOT NULL,
    external_status varchar(80),
    available_start bool DEFAULT false NOT NULL,
    available_withdraw bool DEFAULT false NOT NULL,
    available_pause bool DEFAULT false NOT NULL,
    available_resume bool DEFAULT false NOT NULL,
    available_postpone bool DEFAULT false NOT NULL,
    available_dispute bool DEFAULT false NOT NULL,
    available_contest_dispute bool DEFAULT false NOT NULL,
    available_already_paid bool DEFAULT false NOT NULL,
    last_error varchar(1000),
    started_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    initial_submission_completed bool DEFAULT false NOT NULL,
    submission_attempt_count smallint DEFAULT 0 NOT NULL,
    submission_requires_attention bool DEFAULT false NOT NULL,
    is_parent bool DEFAULT false NOT NULL,
    is_child bool DEFAULT false NOT NULL,
    parent_external_invoice_id int,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE,
    UNIQUE (tenant_id, external_invoice_id),
    UNIQUE (tenant_id, invoice_id)
);

CREATE INDEX ON kravia_collection_cases (tenant_id, created_at DESC);

CREATE TABLE kravia_financial_syncs (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    collection_case_id int NOT NULL,
    operation varchar(30) NOT NULL,
    source_id int NOT NULL,
    event_date date NOT NULL,
    amount numeric(19,4) NOT NULL,
    status varchar(20) DEFAULT 'PENDING' NOT NULL,
    attempt_count int DEFAULT 0 NOT NULL CHECK (attempt_count >= 0),
    last_error varchar(1000),
    credit_note_document_uploaded bool DEFAULT false NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (collection_case_id, tenant_id) REFERENCES kravia_collection_cases(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, collection_case_id, operation, source_id)
);

CREATE INDEX ON kravia_financial_syncs (tenant_id, created_at) WHERE operation = 'CREDIT_NOTE' AND status = 'SENT' AND credit_note_document_uploaded = false;

CREATE INDEX kravia_financial_syncs_tenant_id_created_at_idx1 ON kravia_financial_syncs (tenant_id, created_at) WHERE status IN ('PENDING', 'FAILED');

CREATE TABLE kravia_settlements (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    external_settlement_id int NOT NULL,
    creditor_id int NOT NULL,
    payment_date timestamptz,
    amount numeric(19,2) NOT NULL,
    balance numeric(19,2) NOT NULL,
    currency_code varchar(3) NOT NULL,
    paid_to_bank_account varchar(50),
    bank_account_statement_reference varchar(200),
    invoice_reference_number varchar(200),
    external_status varchar(80),
    external_status_message varchar(200),
    open_for_bookkeeping bool NOT NULL,
    processing_status varchar(30) NOT NULL,
    voucher_id bigint,
    attempt_count int DEFAULT 0 NOT NULL CHECK (attempt_count >= 0),
    last_error varchar(1000),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, external_settlement_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON kravia_settlements (tenant_id, processing_status, payment_date, id) WHERE processing_status IN ('READY', 'RECONCILIATION_FAILED', 'BOOKED', 'ACKNOWLEDGEMENT_FAILED');

CREATE TABLE kravia_settlement_invoice_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    settlement_id int NOT NULL,
    invoice_id int NOT NULL,
    external_invoice_id int NOT NULL,
    receivable_amount numeric(19,2) NOT NULL CHECK (receivable_amount > 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    posting_row_number int CHECK (posting_row_number >= 0),
    UNIQUE (tenant_id, settlement_id, external_invoice_id),
    UNIQUE (tenant_id, settlement_id, invoice_id),
    FOREIGN KEY (settlement_id, tenant_id) REFERENCES kravia_settlements(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar
);

CREATE INDEX ON kravia_settlement_invoice_allocations (tenant_id, invoice_id);

CREATE TABLE kravia_settlement_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    settlement_id int NOT NULL,
    line_number smallint NOT NULL,
    payment_date timestamptz,
    collection_case_number varchar(100),
    external_invoice_ids int[] DEFAULT '{}' NOT NULL,
    amount numeric(19,2) NOT NULL,
    tax_amount numeric(19,2) NOT NULL,
    payment_type smallint,
    pay_type varchar(30),
    recipient varchar(40),
    general_ledger_account_number varchar(20),
    general_ledger_account_description varchar(200),
    description varchar(500),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    vat_code int,
    FOREIGN KEY (settlement_id, tenant_id) REFERENCES kravia_settlements(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, settlement_id, line_number)
);

CREATE TABLE loans (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reference varchar(30) NOT NULL,
    description text,
    loan_type varchar(30) NOT NULL,
    perspective varchar(10) NOT NULL,
    status varchar(15) DEFAULT 'OPEN' NOT NULL,
    currency varchar(3) NOT NULL,
    principal_amount numeric(15,2) NOT NULL CHECK (principal_amount > 0),
    interest_rate_annual numeric(10,6) DEFAULT 0 NOT NULL CHECK (interest_rate_annual >= 0),
    disbursement_date date NOT NULL,
    maturity_date date,
    repayment_type varchar(20) NOT NULL,
    day_count_convention varchar(20) DEFAULT 'ACTUAL_365' NOT NULL,
    interest_treatment varchar(20) DEFAULT 'PAY_SEPARATELY' NOT NULL,
    is_related_party bool DEFAULT false NOT NULL,
    counterparty_name varchar(255) NOT NULL,
    counterparty_type varchar(15) NOT NULL,
    principal_account_number varchar(10) NOT NULL,
    interest_expense_account_number varchar(10),
    interest_income_account_number varchar(10),
    accrued_interest_account_number varchar(10),
    company_bank_id int,
    sub_account_id int,
    outstanding_principal numeric(15,2) DEFAULT 0 NOT NULL,
    accrued_interest_balance numeric(15,2) DEFAULT 0 NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    creditor_id int,
    debtor_id int,
    source_voucher_id bigint,
    opening_balance_date date,
    interest_accrual_blocked_reason varchar(1000),
    PRIMARY KEY (id, tenant_id),
    CHECK (perspective = 'BORROWER' AND creditor_id IS NOT NULL AND debtor_id IS NULL OR perspective = 'LENDER' AND debtor_id IS NOT NULL AND creditor_id IS NULL),
    CONSTRAINT loans_check1 CHECK (opening_balance_date IS NULL AND interest_accrual_blocked_reason IS NULL OR opening_balance_date IS NOT NULL AND interest_accrual_blocked_reason IS NOT NULL AND length(TRIM(BOTH FROM interest_accrual_blocked_reason)) > 0 AND perspective = 'LENDER' AND source_voucher_id IS NOT NULL AND sub_account_id IS NOT NULL AND disbursement_date <= opening_balance_date),
    CONSTRAINT loans_check2 CHECK (maturity_date IS NULL OR maturity_date >= disbursement_date),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (creditor_id, tenant_id) REFERENCES creditors,
    FOREIGN KEY (debtor_id, tenant_id) REFERENCES debtors,
    FOREIGN KEY (source_voucher_id, tenant_id) REFERENCES vouchers,
    FOREIGN KEY (sub_account_id, tenant_id) REFERENCES sub_accounts,
    UNIQUE (tenant_id, reference)
);

CREATE INDEX ON loans (creditor_id);

CREATE INDEX ON loans (debtor_id);

CREATE UNIQUE INDEX ON loans (tenant_id, source_voucher_id) WHERE source_voucher_id IS NOT NULL AND opening_balance_date IS NULL;

CREATE INDEX ON loans (tenant_id, status);

CREATE UNIQUE INDEX ON loans (tenant_id, sub_account_id) WHERE opening_balance_date IS NOT NULL;

CREATE TABLE loan_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    loan_id int NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    FOREIGN KEY (loan_id, tenant_id) REFERENCES loans ON DELETE CASCADE
);

CREATE INDEX ON loan_attachments (loan_id);

CREATE INDEX ON loan_attachments (tenant_id);

CREATE TABLE loan_events (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    loan_id int NOT NULL,
    event_type varchar(30) NOT NULL,
    event_date date NOT NULL,
    principal_amount numeric(15,2) DEFAULT 0 NOT NULL,
    interest_amount numeric(15,2) DEFAULT 0 NOT NULL,
    description text,
    voucher_id bigint,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (loan_id, tenant_id) REFERENCES loans ON DELETE CASCADE,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE INDEX ON loan_events (loan_id);

CREATE TABLE pension_otp (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    voucher_date date NOT NULL,
    description text NOT NULL,
    amount numeric(15,2) NOT NULL,
    project_id int,
    voucher_id bigint,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    source_voucher_id bigint,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (source_voucher_id, tenant_id) REFERENCES vouchers,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON pension_otp (source_voucher_id);

CREATE TABLE postings (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    voucher_id bigint NOT NULL,
    reverses_posting_id bigint,
    reversed_by_posting_id bigint,
    posting_date date NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    supplier_id int,
    project_id int,
    company_bank_id int,
    supplier_invoice_id int,
    customer_id int,
    employee_id int,
    asset_id int,
    department_id int,
    sub_account_id int,
    row_number smallint NOT NULL,
    is_vat_posting bool DEFAULT false NOT NULL,
    account_number varchar(10) NOT NULL CHECK (account_number ~ '^[0-9]+$'),
    amount numeric(15,2) NOT NULL,
    currency char(3) NOT NULL,
    description text,
    vat_code varchar(10) NOT NULL,
    currency_amount numeric(15,2) NOT NULL,
    per_diem_id int,
    vat_basis_amount numeric(15,2),
    FOREIGN KEY (asset_id, tenant_id) REFERENCES assets,
    CHECK (asset_id IS NULL OR account_number IN ('1000', '1001', '1005', '1020', '1021', '1025', '1030', '1031', '1035', '1040', '1041', '1045', '1050', '1051', '1055', '1060', '1061', '1065', '1070', '1071', '1080', '1100', '1105', '1110', '1117', '1120', '1130', '1140', '1150', '1151', '1155', '1160', '1180', '1190', '1200', '1210', '1220', '1221', '1225', '1230', '1231', '1235', '1236', '1237', '1238', '1240', '1241', '1242', '1249', '1250', '1260', '1261', '1265', '1270', '1280', '1290', '1291', '1292')),
    CONSTRAINT postings_check1 CHECK (company_bank_id IS NULL OR account_number IN ('1920', '2380')),
    CONSTRAINT postings_check2 CHECK (customer_id IS NULL OR account_number = '1500'),
    CONSTRAINT postings_check3 CHECK (account_number <> '1500' OR customer_id IS NOT NULL),
    CONSTRAINT postings_check4 CHECK (employee_id IS NULL OR account_number IN ('2910', '2930', '2940')),
    CONSTRAINT postings_check5 CHECK (supplier_id IS NULL OR account_number = '2400'),
    CONSTRAINT postings_check6 CHECK (account_number <> '2400' OR supplier_id IS NOT NULL),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (department_id, tenant_id) REFERENCES departments ON DELETE SET NULL (department_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees,
    FOREIGN KEY (per_diem_id, tenant_id) REFERENCES expense_per_diem_compensations ON DELETE SET NULL (per_diem_id),
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects ON DELETE RESTRICT,
    FOREIGN KEY (sub_account_id, tenant_id, account_number) REFERENCES sub_accounts(id, tenant_id, account_number),
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers,
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE CASCADE
);

CREATE INDEX ON postings (account_number);

CREATE INDEX ON postings (asset_id);

CREATE INDEX ON postings (company_bank_id);

CREATE INDEX ON postings (company_bank_id, posting_date DESC, id) WHERE reverses_posting_id IS NULL AND reversed_by_posting_id IS NULL;

CREATE INDEX ON postings (currency);

CREATE INDEX ON postings (customer_id);

CREATE INDEX ON postings (department_id);

CREATE INDEX ON postings (employee_id);

CREATE UNIQUE INDEX ON postings (id, tenant_id);

CREATE INDEX ON postings (posting_date);

CREATE INDEX ON postings (project_id);

CREATE INDEX ON postings (reversed_by_posting_id) WHERE reversed_by_posting_id IS NOT NULL;

CREATE INDEX ON postings (reverses_posting_id) WHERE reverses_posting_id IS NOT NULL;

CREATE INDEX ON postings (sub_account_id);

CREATE INDEX ON postings (supplier_id);

CREATE INDEX ON postings (supplier_invoice_id);

CREATE INDEX ON postings (tenant_id);

CREATE INDEX ON postings (vat_code);

CREATE INDEX ON postings (voucher_id);

CREATE CONSTRAINT TRIGGER postings_balance_per_posting_date AFTER INSERT OR DELETE OR UPDATE OF amount, posting_date, voucher_id ON postings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ensure_postings_balance_per_posting_date();

CREATE CONSTRAINT TRIGGER postings_require_voucher AFTER DELETE OR UPDATE OF voucher_id ON postings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ensure_postings_do_not_leave_empty_voucher();

ALTER TABLE postings ADD FOREIGN KEY (reversed_by_posting_id, tenant_id) REFERENCES postings(id, tenant_id);

ALTER TABLE postings ADD FOREIGN KEY (reverses_posting_id, tenant_id) REFERENCES postings(id, tenant_id);

CREATE TABLE driving_log_trips (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int,
    trip_date date NOT NULL,
    from_location varchar(200) NOT NULL CHECK (length(btrim(from_location)) > 0),
    to_location varchar(200) NOT NULL CHECK (length(btrim(to_location)) > 0),
    purpose text NOT NULL CHECK (length(btrim(purpose)) > 0),
    kilometers numeric(9,1) NOT NULL CHECK (kilometers > 0),
    rate_per_km numeric(9,2) NOT NULL,
    amount numeric(15,2) NOT NULL,
    voucher_id bigint,
    deleted_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    project_id int,
    road_toll_amount numeric(15,2) DEFAULT 0 NOT NULL CHECK (road_toll_amount >= 0),
    vehicle_id int,
    accounting_mode varchar(30) DEFAULT 'MILEAGE_ALLOWANCE' NOT NULL,
    odometer_start numeric(10,1),
    odometer_end numeric(10,1),
    passengers jsonb DEFAULT '[]' NOT NULL,
    equipment_purpose varchar(1000) DEFAULT '' NOT NULL,
    equipment_kilometers numeric(9,1) DEFAULT 0 NOT NULL,
    supplement_rate_per_km numeric(9,2) DEFAULT 0 NOT NULL,
    payroll_posting_id bigint,
    agreed_rate_per_km numeric(9,2) CHECK (agreed_rate_per_km > 0 AND agreed_rate_per_km <= 9999.99),
    CHECK (accounting_mode = 'MILEAGE_ALLOWANCE' AND amount > 0 AND rate_per_km > 0 OR accounting_mode = 'LOG_ONLY' AND amount = 0 AND rate_per_km = 0 AND voucher_id IS NULL),
    CONSTRAINT driving_log_trips_check1 CHECK (odometer_start IS NULL AND odometer_end IS NULL OR odometer_start IS NOT NULL AND odometer_end IS NOT NULL AND odometer_start >= 0 AND odometer_end > odometer_start AND odometer_end - odometer_start = kilometers),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees,
    FOREIGN KEY (payroll_posting_id, tenant_id) REFERENCES postings(id, tenant_id),
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (vehicle_id, tenant_id) REFERENCES driving_log_vehicles(id, tenant_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON driving_log_trips (employee_id);

CREATE INDEX ON driving_log_trips (payroll_posting_id) WHERE payroll_posting_id IS NOT NULL;

CREATE INDEX ON driving_log_trips (project_id);

CREATE INDEX ON driving_log_trips (tenant_id, trip_date DESC, id DESC) WHERE deleted_at IS NULL;

CREATE INDEX ON driving_log_trips (tenant_id, vehicle_id, trip_date) WHERE deleted_at IS NULL;

CREATE INDEX ON driving_log_trips (voucher_id);

CREATE TABLE posting_group_postings (
    posting_id bigint PRIMARY KEY,
    posting_group_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (posting_group_id, tenant_id) REFERENCES posting_groups ON DELETE CASCADE,
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON posting_group_postings (posting_group_id, tenant_id);

CREATE INDEX ON posting_group_postings (tenant_id);

CREATE TRIGGER invoice_activity_touch_from_posting_group AFTER INSERT OR DELETE OR UPDATE ON posting_group_postings FOR EACH ROW EXECUTE FUNCTION touch_invoices_from_posting_group_change();

CREATE TRIGGER posting_group_postings_delete_empty_group AFTER DELETE ON posting_group_postings FOR EACH ROW EXECUTE FUNCTION delete_empty_posting_group_after_link_delete();

CREATE TRIGGER preserve_supplier_posting_group_membership BEFORE INSERT OR UPDATE ON posting_group_postings FOR EACH ROW EXECUTE FUNCTION validate_supplier_posting_group_membership();

CREATE TABLE reai_system_subscription_periods (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    reai_system_subscription_id int NOT NULL,
    access_type varchar(20) NOT NULL,
    plan_id int REFERENCES reai_system_subscription_plans,
    invoice_id int CHECK (invoice_id IS NULL OR invoice_id > 0),
    correction_invoice_id int CHECK (correction_invoice_id IS NULL OR correction_invoice_id > 0),
    generic_subscription_tenant_id int CHECK (generic_subscription_tenant_id IS NULL OR generic_subscription_tenant_id IN (1337, 1239)),
    generic_subscription_id int,
    generic_subscription_coverage_type varchar(30) CHECK (generic_subscription_coverage_type IS NULL OR generic_subscription_coverage_type IN ('DIRECT', 'SERVICE_RECIPIENT')),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    cancelled_at timestamptz,
    plan_voucher_count int CHECK (plan_voucher_count IS NULL OR plan_voucher_count >= 0),
    plan_amount_ex_vat numeric(12,2) CHECK (plan_amount_ex_vat IS NULL OR plan_amount_ex_vat >= 0),
    correction_amount_ex_vat numeric(12,2),
    invoice_tenant_id int GENERATED ALWAYS AS (
CASE
    WHEN invoice_id IS NULL THEN NULL
    ELSE 1239
END) STORED,
    correction_invoice_tenant_id int GENERATED ALWAYS AS (
CASE
    WHEN correction_invoice_id IS NULL THEN NULL
    ELSE 1239
END) STORED,
    CONSTRAINT reai_system_subscription_per_reai_system_subscription_id__excl1 EXCLUDE USING gist (reai_system_subscription_id WITH =, tstzrange(starts_at, COALESCE(cancelled_at, ends_at, 'infinity'::timestamp with time zone), '[)'::text) WITH &&) WHERE (access_type::text <> ALL (ARRAY['GENERIC_SUBSCRIPTION'::character varying::text, 'EXEMPT'::character varying::text, 'SHOPIFY'::character varying::text])),
    CONSTRAINT reai_system_subscription_per_reai_system_subscription_id__excl2 EXCLUDE USING gist (reai_system_subscription_id WITH =, tstzrange(starts_at, COALESCE(cancelled_at, ends_at, 'infinity'::timestamp with time zone), '[)'::text) WITH &&) WHERE (access_type::text = 'SHOPIFY'::text),
    CONSTRAINT reai_system_subscription_per_reai_system_subscription_id__excl3 EXCLUDE USING gist (reai_system_subscription_id WITH =, tstzrange(starts_at, COALESCE(cancelled_at, ends_at, 'infinity'::timestamp with time zone), '[)'::text) WITH &&) WHERE (access_type::text = 'GENERIC_SUBSCRIPTION'::text),
    FOREIGN KEY (correction_invoice_id, correction_invoice_tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (invoice_id, invoice_tenant_id) REFERENCES invoices_ar,
    EXCLUDE USING gist (reai_system_subscription_id WITH =, tstzrange(starts_at, COALESCE(cancelled_at, ends_at, 'infinity'::timestamp with time zone), '[)'::text) WITH &&) WHERE (access_type::text = 'EXEMPT'::text),
    FOREIGN KEY (reai_system_subscription_id, tenant_id) REFERENCES reai_system_subscriptions(id, tenant_id) ON DELETE CASCADE,
    CHECK (cancelled_at IS NULL OR cancelled_at >= starts_at AND (ends_at IS NULL OR cancelled_at <= ends_at)),
    CONSTRAINT reai_system_subscription_periods_check1 CHECK (access_type <> 'PAID' OR plan_id IS NOT NULL AND plan_voucher_count IS NOT NULL AND plan_amount_ex_vat IS NOT NULL),
    CONSTRAINT reai_system_subscription_periods_check2 CHECK ((access_type = 'GENERIC_SUBSCRIPTION') = (generic_subscription_tenant_id IS NOT NULL AND generic_subscription_id IS NOT NULL AND generic_subscription_coverage_type IS NOT NULL)),
    UNIQUE (id, tenant_id)
);

CREATE INDEX ON reai_system_subscription_periods (generic_subscription_tenant_id, generic_subscription_id) WHERE generic_subscription_id IS NOT NULL;

CREATE INDEX ON reai_system_subscription_periods (reai_system_subscription_id, starts_at DESC);

CREATE INDEX ON reai_system_subscription_periods (correction_invoice_id) WHERE correction_invoice_id IS NOT NULL;

CREATE INDEX ON reai_system_subscription_periods (invoice_id) WHERE invoice_id IS NOT NULL;

CREATE TABLE reai_system_subscription_emails (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    reai_system_subscription_period_id int NOT NULL REFERENCES reai_system_subscription_periods ON DELETE CASCADE,
    email_type varchar(30) NOT NULL,
    sent_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ON reai_system_subscription_emails (reai_system_subscription_period_id, email_type);

CREATE TABLE salary_payments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    number smallint NOT NULL,
    period_year smallint NOT NULL,
    period_month smallint NOT NULL CHECK (period_month >= 1 AND period_month <= 12),
    voucher_id bigint,
    payment_date date,
    voucher_date date DEFAULT CURRENT_DATE NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    finalized_at timestamptz,
    import_source varchar(100),
    import_source_instance varchar(200),
    external_id varchar(200),
    import_fingerprint varchar(64),
    import_payload jsonb,
    reversal_requested_at timestamptz,
    PRIMARY KEY (id, tenant_id),
    CHECK (num_nonnulls(import_source, import_source_instance, external_id, import_fingerprint, import_payload) IN (0, 5) AND (import_payload IS NULL OR jsonb_typeof(import_payload) = 'object')),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE INDEX ON salary_payments (period_year, period_month);

CREATE UNIQUE INDEX ON salary_payments (tenant_id, import_source, import_source_instance, external_id) WHERE import_source IS NOT NULL;

CREATE UNIQUE INDEX ON salary_payments (tenant_id, period_year, number);

CREATE TABLE employee_recurring_post_tax_deduction_applications (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    recurring_post_tax_deduction_id int NOT NULL,
    salary_payment_id int NOT NULL,
    period_year smallint NOT NULL,
    period_month smallint NOT NULL CHECK (period_month >= 1 AND period_month <= 12),
    FOREIGN KEY (recurring_post_tax_deduction_id, tenant_id) REFERENCES employee_recurring_post_tax_deductions ON DELETE CASCADE,
    FOREIGN KEY (salary_payment_id, tenant_id) REFERENCES salary_payments ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON employee_recurring_post_tax_deduction_applications (tenant_id, recurring_post_tax_deduction_id, period_year, period_month);

CREATE INDEX ON employee_recurring_post_tax_deduction_applications (tenant_id, salary_payment_id);

CREATE TABLE employee_salary_payments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    salary_payment_id int NOT NULL,
    employee_id int NOT NULL,
    company_bank_id int,
    tax_deducted numeric(15,2) DEFAULT 0 NOT NULL,
    import_gross_salary numeric(15,2),
    import_net_paid numeric(15,2),
    import_holiday_pay_basis numeric(15,2),
    import_holiday_pay_accrued numeric(15,2),
    tax_deduction_override_reason varchar(500),
    tax_deduction_override_fingerprint varchar(64),
    holiday_pay_basis numeric(27,4),
    holiday_pay_rate numeric(9,6),
    holiday_pay_accrued numeric(15,2),
    holiday_pay_employer_insurance numeric(15,2),
    tax_table_number varchar(10),
    PRIMARY KEY (id, tenant_id),
    CHECK (num_nonnulls(tax_deduction_override_reason, tax_deduction_override_fingerprint) IN (0, 2)),
    CONSTRAINT employee_salary_payments_check1 CHECK (num_nonnulls(import_gross_salary, import_net_paid, import_holiday_pay_basis, import_holiday_pay_accrued) IN (0, 4)),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees,
    UNIQUE (salary_payment_id, employee_id),
    FOREIGN KEY (salary_payment_id, tenant_id) REFERENCES salary_payments
);

CREATE INDEX ON employee_salary_payments (company_bank_id);

CREATE INDEX ON employee_salary_payments (employee_id);

CREATE TABLE employee_wage_specifications (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_salary_payment_id int NOT NULL,
    specification_code varchar(50) NOT NULL,
    quantity numeric(12,2) DEFAULT 0 NOT NULL CHECK (quantity >= 0),
    rate numeric(15,2) DEFAULT 0 NOT NULL,
    posting_id bigint,
    comment text,
    holiday_allowance_earning_year smallint,
    recurring_post_tax_deduction_id int,
    deduction_account_number varchar(4),
    deduction_agreement_reference varchar(500),
    reportable_days smallint,
    CHECK (holiday_allowance_earning_year IS NULL OR specification_code IN ('HOLIDAY_ALLOWANCE', 'NONPROFIT_HOLIDAY_ALLOWANCE')),
    CONSTRAINT employee_wage_specifications_check1 CHECK (specification_code <> 'POST_TAX_DEDUCTION' OR quantity > 0 AND rate < 0),
    CONSTRAINT employee_wage_specifications_check2 CHECK (specification_code = 'POST_TAX_DEDUCTION' AND deduction_account_number ~ '^[0-9]{4}$' AND NULLIF(btrim(deduction_agreement_reference), '') IS NOT NULL OR specification_code <> 'POST_TAX_DEDUCTION' AND recurring_post_tax_deduction_id IS NULL AND deduction_account_number IS NULL AND deduction_agreement_reference IS NULL),
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments,
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id) ON DELETE SET NULL (posting_id),
    FOREIGN KEY (recurring_post_tax_deduction_id, tenant_id) REFERENCES employee_recurring_post_tax_deductions
);

CREATE INDEX ON employee_wage_specifications (employee_salary_payment_id);

CREATE UNIQUE INDEX ON employee_wage_specifications (employee_salary_payment_id, recurring_post_tax_deduction_id) WHERE recurring_post_tax_deduction_id IS NOT NULL;

CREATE INDEX ON employee_wage_specifications (posting_id);

CREATE TABLE imported_payslips (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    employee_salary_payment_id int NOT NULL,
    filename varchar(200) NOT NULL,
    sha256 varchar(64) NOT NULL,
    file_data bytea NOT NULL,
    size int NOT NULL,
    uploaded_at timestamptz DEFAULT now() NOT NULL,
    CHECK (((size = octet_length(file_data)) AND ((size >= 1) AND (size <= 10485760)))),
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments ON DELETE CASCADE,
    UNIQUE (employee_salary_payment_id, tenant_id)
);

CREATE TABLE payslips (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    number smallint NOT NULL,
    year smallint NOT NULL,
    date date NOT NULL,
    employee_salary_payment_id int NOT NULL,
    available_at date,
    email_outbox_id int,
    FOREIGN KEY (email_outbox_id, tenant_id) REFERENCES email_outbox(id, sender_tenant_id),
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments
);

CREATE INDEX ON payslips (employee_salary_payment_id, year);

CREATE INDEX ON payslips (tenant_id);

CREATE UNIQUE INDEX ON payslips (year, number, tenant_id, employee_salary_payment_id);

CREATE TABLE salary_holiday_accrual_corrections (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_salary_payment_id int NOT NULL,
    voucher_id bigint NOT NULL,
    idempotency_key uuid NOT NULL,
    posting_date date NOT NULL,
    basis numeric(27,4) NOT NULL,
    previous_accrued numeric(15,2) NOT NULL,
    rate_percent numeric(9,6) NOT NULL,
    accrued numeric(15,2) NOT NULL,
    employer_insurance_rate_percent numeric(9,6) NOT NULL,
    previous_employer_insurance numeric(15,2) NOT NULL,
    employer_insurance numeric(15,2) NOT NULL,
    reason varchar(500) NOT NULL,
    created_by_user_id int NOT NULL REFERENCES users,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments,
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON salary_holiday_accrual_corrections (employee_salary_payment_id, tenant_id);

CREATE INDEX ON salary_holiday_accrual_corrections (voucher_id, tenant_id);

CREATE TABLE salary_reporting_corrections (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_salary_payment_id int NOT NULL,
    first_period date NOT NULL,
    last_period date NOT NULL,
    request_id uuid NOT NULL,
    preview jsonb NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
    reason varchar(2000) NOT NULL CHECK (length(TRIM(BOTH FROM reason)) > 0),
    created_by_user_id int NOT NULL REFERENCES users,
    created_at timestamptz DEFAULT now() NOT NULL,
    CHECK (last_period >= first_period),
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments,
    UNIQUE (tenant_id, employee_salary_payment_id),
    UNIQUE (tenant_id, request_id)
);

CREATE INDEX ON salary_reporting_corrections (tenant_id, first_period, last_period);

CREATE TABLE shareholder_events (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    report_year smallint NOT NULL CHECK (report_year >= 1900),
    event_type varchar(64) NOT NULL,
    event_date date NOT NULL,
    event_time time,
    total_amount numeric(18,2) DEFAULT 0 NOT NULL CHECK (total_amount >= 0),
    per_share_amount numeric(18,6) DEFAULT 0 NOT NULL CHECK (per_share_amount >= 0),
    shareholders_impacted int DEFAULT 0 NOT NULL CHECK (shareholders_impacted >= 0),
    notes varchar(1024),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    share_class_code varchar(2) DEFAULT '01' NOT NULL,
    voucher_id bigint,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON shareholder_events (tenant_id, report_year, share_class_code);

CREATE INDEX ON shareholder_events (voucher_id);

CREATE TABLE shopify_consolidated_sales_record (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year smallint NOT NULL,
    month smallint NOT NULL,
    fetched_at timestamptz NOT NULL,
    voucher_id bigint,
    voucher_number text,
    voucher_date date,
    UNIQUE (tenant_id, year, month),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE CASCADE
);

CREATE TABLE supplier_invoice_related_postings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    supplier_invoice_id int NOT NULL,
    posting_id bigint NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE CASCADE,
    UNIQUE (tenant_id, supplier_invoice_id, posting_id),
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, posting_id)
);

CREATE TABLE supplier_posting_group_postings (
    posting_id bigint PRIMARY KEY,
    posting_group_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (posting_group_id, tenant_id) REFERENCES posting_groups ON DELETE CASCADE,
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON supplier_posting_group_postings (posting_group_id, tenant_id);

CREATE INDEX ON supplier_posting_group_postings (tenant_id);

CREATE TRIGGER reopen_supplier_group_after_member_delete AFTER DELETE ON supplier_posting_group_postings FOR EACH ROW EXECUTE FUNCTION reopen_supplier_group_after_member_delete();

CREATE TRIGGER validate_supplier_posting_group_membership BEFORE INSERT OR UPDATE ON supplier_posting_group_postings FOR EACH ROW EXECUTE FUNCTION validate_supplier_posting_group_membership();

CREATE TABLE transactions (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reconciled_at timestamptz,
    voucher_id bigint,
    id int GENERATED BY DEFAULT AS IDENTITY,
    reconciled_by int,
    transaction_date date NOT NULL,
    bank_company_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    auto_reconciliation_excluded bool DEFAULT false NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount <> 0),
    currency char(3) NOT NULL,
    provider_type varchar(50),
    provider_transaction_id text,
    description text,
    payment_reference varchar(255),
    payment_message varchar(255),
    end_to_end_id varchar(100),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (bank_company_id, tenant_id) REFERENCES company_bank ON DELETE CASCADE,
    UNIQUE (provider_transaction_id, bank_company_id, transaction_date, amount, currency),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE INDEX ON transactions (bank_company_id, currency, amount, transaction_date DESC) WHERE voucher_id IS NULL;

CREATE INDEX ON transactions (bank_company_id, end_to_end_id);

CREATE INDEX ON transactions (bank_company_id, transaction_date DESC, id) WHERE voucher_id IS NULL;

CREATE INDEX ON transactions (bank_company_id, transaction_date DESC);

CREATE INDEX ON transactions (tenant_id);

CREATE INDEX ON transactions (voucher_id);

CREATE TABLE transaction_postings (
    posting_id bigint NOT NULL,
    reconciled_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    transaction_id int NOT NULL,
    reconciled_by int,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reconciliation_group_id int NOT NULL,
    match_source varchar(40) CHECK (match_source IS NULL OR match_source IN ('MANUAL_POSTING_LINK', 'RECONCILIATION_GENERATED_VOUCHER', 'RECONCILIATION_RULE_VOUCHER', 'VAT_REFUND_AUTO_MATCH')),
    PRIMARY KEY (transaction_id, posting_id),
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (reconciliation_group_id, tenant_id) REFERENCES reconciliation_groups ON DELETE CASCADE,
    FOREIGN KEY (transaction_id, tenant_id) REFERENCES transactions ON DELETE CASCADE
);

CREATE INDEX ON transaction_postings (posting_id);

CREATE INDEX ON transaction_postings (reconciliation_group_id, tenant_id);

CREATE INDEX ON transaction_postings (tenant_id);

CREATE TRIGGER transaction_postings_delete_empty_reconciliation_group AFTER DELETE ON transaction_postings FOR EACH ROW EXECUTE FUNCTION delete_empty_reconciliation_group_after_link_delete();

CREATE TABLE vat_submissions (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    year int NOT NULL,
    period varchar(20) NOT NULL,
    output_vat_amount numeric(15,2) DEFAULT 0.00 NOT NULL,
    input_vat_amount numeric(15,2) DEFAULT 0.00 NOT NULL,
    delivery_method varchar(15) DEFAULT 'ALTINN' NOT NULL,
    altinn_instance_id varchar(255),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sent_at timestamptz,
    receipt_received_at timestamptz,
    last_voucher_id bigint,
    payment_kid_number varchar(255),
    payment_amount numeric(15,2),
    payment_pay_to text,
    payment_deadline date,
    payment_pay_to_account_number varchar(50),
    receipt_pdf_data bytea,
    external_filing_reference varchar(255),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (last_voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (last_voucher_id),
    UNIQUE (tenant_id, year, period)
);

CREATE TABLE payments (
    voucher_id bigint,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_status_check_at timestamptz,
    dispatch_date timestamptz,
    funds_notified_at timestamptz,
    failure_notification_queued_at timestamptz,
    failure_notified_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int,
    company_bank_id int NOT NULL,
    transfer_date date NOT NULL,
    salary_payment_id int,
    credit_note_id int,
    created_by_user_id int REFERENCES users ON DELETE SET NULL,
    vat_submission_id int,
    refunded_payment_id int,
    created_with_consent_id int,
    amelding_id int,
    loan_id int,
    is_manual bool DEFAULT false NOT NULL,
    payment_status varchar(30) NOT NULL,
    provider_type varchar(50) NOT NULL,
    currency char(3) NOT NULL,
    payment_amount numeric(15,2) NOT NULL,
    provider_payment_id varchar(255) NOT NULL UNIQUE,
    fee_amount numeric(15,2),
    fee_currency varchar(3),
    exchange_rate numeric(15,6),
    failure_reason text,
    reference varchar(140) NOT NULL,
    payment_currency_amount numeric(15,2),
    idempotency_key varchar(64),
    end_to_end_id varchar(100),
    loan_principal_amount numeric(15,2),
    loan_interest_amount numeric(15,2),
    failure_code varchar(40),
    tax_debt_due_date_identifier varchar(50),
    accounting_intent jsonb CHECK (accounting_intent IS NULL OR
CASE
    WHEN jsonb_typeof(accounting_intent) = 'object' AND jsonb_typeof(accounting_intent -> 'postings') = 'array' THEN jsonb_array_length(accounting_intent -> 'postings') > 0
    ELSE false
END),
    debt_sub_account_id int,
    remittance_instructions jsonb,
    recipient_message varchar(140),
    FOREIGN KEY (amelding_id, tenant_id) REFERENCES a_melding ON DELETE SET NULL (amelding_id),
    CHECK (failure_notified_at IS NULL OR failure_notification_queued_at IS NOT NULL AND failure_notified_at >= failure_notification_queued_at),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank ON DELETE RESTRICT,
    FOREIGN KEY (created_with_consent_id, tenant_id) REFERENCES ztl_consent,
    FOREIGN KEY (credit_note_id, tenant_id) REFERENCES invoices_ar ON DELETE SET NULL (credit_note_id),
    FOREIGN KEY (debt_sub_account_id, tenant_id) REFERENCES sub_accounts,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE RESTRICT,
    FOREIGN KEY (loan_id, tenant_id) REFERENCES loans ON DELETE SET NULL (loan_id),
    FOREIGN KEY (salary_payment_id, tenant_id) REFERENCES salary_payments ON DELETE SET NULL (salary_payment_id),
    FOREIGN KEY (vat_submission_id, tenant_id) REFERENCES vat_submissions ON DELETE SET NULL (vat_submission_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE SET NULL (voucher_id)
);

CREATE INDEX ON payments (amelding_id);

CREATE UNIQUE INDEX ON payments (amelding_id, reference) WHERE amelding_id IS NOT NULL AND payment_status <> ALL (ARRAY['FAILED', 'REVERSED'::varchar]);

CREATE INDEX ON payments (company_bank_id, end_to_end_id);

CREATE INDEX ON payments (created_by_user_id);

CREATE INDEX ON payments (created_with_consent_id);

CREATE INDEX ON payments (credit_note_id);

CREATE INDEX ON payments (failure_notification_queued_at) WHERE failure_notification_queued_at IS NOT NULL AND failure_notified_at IS NULL;

CREATE UNIQUE INDEX ON payments (id, tenant_id);

CREATE INDEX ON payments (invoice_id);

CREATE INDEX ON payments (loan_id) WHERE loan_id IS NOT NULL;

CREATE INDEX ON payments (payment_status) WHERE payment_status = 'IN_PROCESS';

CREATE INDEX payments_payment_status_idx1 ON payments (payment_status) WHERE payment_status IN ('IN_PROCESS', 'AWAITING_APPROVAL', 'CUSTOMER_ACTION_REQUIRED');

CREATE INDEX ON payments (refunded_payment_id);

CREATE INDEX ON payments (salary_payment_id);

CREATE INDEX ON payments (tenant_id, debt_sub_account_id) WHERE debt_sub_account_id IS NOT NULL;

CREATE UNIQUE INDEX ON payments (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL AND payment_status <> 'FAILED';

CREATE INDEX ON payments (tenant_id, payment_status, transfer_date DESC, company_bank_id, id);

CREATE INDEX ON payments (tenant_id, tax_debt_due_date_identifier) WHERE tax_debt_due_date_identifier IS NOT NULL;

CREATE INDEX ON payments (vat_submission_id);

CREATE INDEX ON payments (voucher_id);

ALTER TABLE payments ADD FOREIGN KEY (refunded_payment_id, tenant_id) REFERENCES payments(id, tenant_id) ON DELETE SET NULL (refunded_payment_id);

CREATE TABLE invoice_ar_payments (
    id int GENERATED BY DEFAULT AS IDENTITY,
    voucher_id bigint,
    invoice_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_date date NOT NULL,
    paid_amount numeric(10,2) NOT NULL,
    received_amount numeric(10,2) NOT NULL,
    bank_fee numeric(10,2),
    company_bank_id int,
    bank_fee_in_currency numeric(10,2),
    settlement_sub_account_id int,
    settlement_provider varchar(30),
    settlement_environment varchar(4),
    settlement_transaction_id text,
    import_reference varchar(255),
    outgoing_payment_id int,
    PRIMARY KEY (id, tenant_id),
    CHECK (settlement_sub_account_id IS NULL OR company_bank_id IS NULL),
    CONSTRAINT invoice_ar_payments_check1 CHECK (settlement_provider IS NULL AND settlement_environment IS NULL AND settlement_transaction_id IS NULL AND settlement_sub_account_id IS NULL OR settlement_provider IN ('ADYEN', 'KUSTOM') AND settlement_environment IN ('TEST', 'LIVE') AND settlement_transaction_id IS NOT NULL AND settlement_transaction_id <> '' AND settlement_sub_account_id IS NOT NULL),
    CONSTRAINT invoice_ar_payments_check2 CHECK (import_reference IS NULL OR length(TRIM(BOTH FROM import_reference)) > 0 AND voucher_id IS NULL),
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (outgoing_payment_id, tenant_id) REFERENCES payments(id, tenant_id),
    FOREIGN KEY (settlement_sub_account_id, tenant_id) REFERENCES sub_accounts,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE INDEX ON invoice_ar_payments (invoice_id);

CREATE UNIQUE INDEX ON invoice_ar_payments (outgoing_payment_id, tenant_id) WHERE outgoing_payment_id IS NOT NULL;

CREATE UNIQUE INDEX ON invoice_ar_payments (tenant_id, import_reference) WHERE import_reference IS NOT NULL;

CREATE UNIQUE INDEX ON invoice_ar_payments (tenant_id, settlement_provider, settlement_environment, settlement_transaction_id) WHERE settlement_transaction_id IS NOT NULL;

CREATE TRIGGER invoice_ar_payments_touch_invoice AFTER INSERT OR DELETE OR UPDATE ON invoice_ar_payments FOR EACH ROW EXECUTE FUNCTION touch_invoice_from_related_row();

CREATE TABLE payment_approval_batch_payment (
    batch_id text NOT NULL REFERENCES payment_approval_batch ON DELETE CASCADE,
    payment_id int NOT NULL REFERENCES payments ON DELETE CASCADE,
    PRIMARY KEY (batch_id, payment_id)
);

CREATE TABLE payment_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE RESTRICT,
    UNIQUE (payment_id, attachment_id),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON payment_attachments (attachment_id, tenant_id);

CREATE INDEX ON payment_attachments (tenant_id);

CREATE TABLE payment_beneficiary_infos (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    account_number varchar(100),
    iban varchar(50) CHECK (iban IS NULL OR length(btrim(iban)) > 0),
    swift_code varchar(20) CHECK (swift_code IS NULL OR length(btrim(swift_code)) > 0),
    bank_name varchar(255),
    routing_number varchar(100),
    bank_country_code varchar(3),
    street_address varchar(255),
    city varchar(100),
    postcode varchar(20),
    country varchar(3),
    state varchar(100),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON payment_beneficiary_infos (tenant_id, name);

CREATE TABLE payment_postings (
    posting_id bigint NOT NULL REFERENCES postings ON DELETE CASCADE UNIQUE,
    payment_id int NOT NULL REFERENCES payments ON DELETE CASCADE,
    PRIMARY KEY (payment_id, posting_id)
);

CREATE TABLE payment_status_logs (
    "timestamp" timestamptz NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL,
    triggered_by int,
    from_provider_status varchar(50),
    to_provider_status varchar(50) NOT NULL,
    trigger_type varchar(20) NOT NULL,
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON payment_status_logs (payment_id);

CREATE INDEX ON payment_status_logs (tenant_id);

CREATE TABLE salary_remainder_settlements (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_salary_payment_id int NOT NULL,
    payment_id int NOT NULL,
    request_id uuid NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    transfer_date date NOT NULL,
    company_bank_id int NOT NULL,
    reason varchar(1000) NOT NULL,
    created_by_user_id int NOT NULL REFERENCES users,
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (company_bank_id, tenant_id) REFERENCES company_bank,
    FOREIGN KEY (employee_salary_payment_id, tenant_id) REFERENCES employee_salary_payments,
    UNIQUE (employee_salary_payment_id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments(id, tenant_id),
    UNIQUE (tenant_id, request_id)
);

CREATE TABLE salary_remainder_sources (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    settlement_id int NOT NULL,
    posting_id bigint NOT NULL,
    FOREIGN KEY (posting_id, tenant_id) REFERENCES postings(id, tenant_id),
    UNIQUE (posting_id, tenant_id),
    FOREIGN KEY (settlement_id, tenant_id) REFERENCES salary_remainder_settlements(id, tenant_id)
);

CREATE INDEX ON salary_remainder_sources (settlement_id, tenant_id);

CREATE TABLE vat_settlement_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    submission_id int,
    year int NOT NULL,
    period varchar(20) NOT NULL,
    reference varchar(200) NOT NULL,
    kind varchar(30) NOT NULL CHECK (kind IN ('posting', 'historical_offset')),
    source_posting_id bigint NOT NULL,
    source_voucher_id bigint NOT NULL,
    source_amount numeric(15,2) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount <> 0),
    evidence text NOT NULL CHECK (length(TRIM(BOTH FROM evidence)) > 0),
    settled_on date NOT NULL,
    created_at timestamptz NOT NULL,
    created_by int NOT NULL,
    reversed_at timestamptz,
    reversed_by int,
    reversal_reason text,
    CHECK (sign(amount) = sign(source_amount) AND abs(amount) <= abs(source_amount)),
    CONSTRAINT vat_settlement_allocations_check1 CHECK (reversed_at IS NULL AND reversed_by IS NULL AND reversal_reason IS NULL OR reversed_at IS NOT NULL AND reversed_by IS NOT NULL AND reversal_reason IS NOT NULL AND length(TRIM(BOTH FROM reversal_reason)) > 0),
    FOREIGN KEY (source_posting_id, tenant_id) REFERENCES postings(id, tenant_id),
    FOREIGN KEY (source_voucher_id, tenant_id) REFERENCES vouchers,
    FOREIGN KEY (submission_id, tenant_id) REFERENCES vat_submissions ON DELETE SET NULL (submission_id),
    UNIQUE (tenant_id, reference)
);

CREATE INDEX ON vat_settlement_allocations (source_posting_id);

CREATE INDEX ON vat_settlement_allocations (submission_id);

CREATE TABLE voucher_attachments (
    voucher_id bigint NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    attachment_id int NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE CASCADE
);

CREATE INDEX ON voucher_attachments (tenant_id);

CREATE INDEX ON voucher_attachments (voucher_id);

CREATE TABLE voucher_reception_document_invoice_analysis (
    voucher_reception_document_id int CONSTRAINT voucher_reception_document_voucher_reception_document_not_null1 NOT NULL PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    voucher_type varchar(50) NOT NULL,
    invoice_number varchar(255) NOT NULL,
    invoice_date date NOT NULL,
    invoice_due_date date,
    invoice_kid_number varchar(255),
    account_number varchar(255),
    swift_bic varchar(255),
    company_name varchar(255) NOT NULL,
    company_number varchar(255),
    invoice_order_total numeric(15,2) NOT NULL,
    invoice_currency varchar(10) NOT NULL,
    invoice_vat_percentage int,
    invoice_vat_code varchar(50),
    invoice_vat_amount numeric(15,2),
    invoice_description text,
    invoice_supplier_id varchar(255),
    invoice_document_type varchar(50),
    invoice_is_credit_note bool NOT NULL,
    invoice_total_amount_with_25_percent_vat numeric(15,2),
    invoice_total_amount_with_15_percent_vat numeric(15,2),
    invoice_total_amount_with_12_percent_vat numeric(15,2),
    invoice_total_amount_with_0_percent_vat numeric(15,2),
    payment_iban varchar(255),
    payment_bank_name varchar(255),
    payment_bank_country_code varchar(10),
    payment_routing_number varchar(255),
    payment_routing_type varchar(255),
    payment_reference varchar(255),
    company_street_address varchar(255),
    company_city varchar(255),
    company_state varchar(255),
    company_postcode varchar(255),
    company_country_code varchar(10),
    debit_account varchar(255) NOT NULL,
    debit_vat_code varchar(50),
    accrual_period_start varchar(20),
    accrual_period_end varchar(20),
    accrual_period_count int,
    income_prefill_customer_id varchar(255),
    income_prefill_bank_fee numeric(10,2),
    receipt_company_currency_amount numeric(15,2),
    receipt_company_currency char(3),
    receipt_matched_transaction_id int,
    invoice_rounding_amount numeric(15,2),
    reviewed_debit_account varchar(10),
    prediction_review_required bool DEFAULT false NOT NULL,
    cost_predictions jsonb,
    prediction_justification text,
    FOREIGN KEY (receipt_matched_transaction_id, tenant_id) REFERENCES transactions ON DELETE SET NULL (receipt_matched_transaction_id),
    FOREIGN KEY (voucher_reception_document_id, tenant_id) REFERENCES voucher_reception_documents ON DELETE CASCADE
);

CREATE INDEX ON voucher_reception_document_invoice_analysis (receipt_matched_transaction_id, tenant_id) WHERE receipt_matched_transaction_id IS NOT NULL;

CREATE INDEX ON voucher_reception_document_invoice_analysis (tenant_id);

CREATE TABLE ztl_payments (
    status_at timestamptz,
    quote_expiry_time timestamptz,
    recreated_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL UNIQUE,
    created_with_consent_user_id int,
    approved_by_user_id int,
    approved_with_consent_id int,
    batch_approval bool DEFAULT false NOT NULL,
    recreated bool DEFAULT false NOT NULL,
    payment_type varchar(20),
    status varchar(50),
    status_reason varchar(100),
    status_reason_text text,
    cost_currency varchar(3),
    created_with_consent_uuid varchar(100),
    created_with_consent_bic varchar(20),
    approval_id varchar(100),
    approval_status varchar(50),
    approved_with_consent_bic varchar(20),
    cancellation_id varchar(100),
    cancellation_request_status varchar(50),
    original_provider_payment_id varchar(255),
    recreation_reason text,
    cancellation_sca_returned_at timestamptz,
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON ztl_payments (tenant_id);

CREATE TABLE ztl_transactions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    transaction_id int NOT NULL UNIQUE,
    booking_date date,
    value_date date,
    entry_type varchar(10),
    entry_status varchar(50),
    purpose_text text,
    creditor_name varchar(255),
    creditor_iban varchar(34),
    creditor_bban varchar(50),
    debtor_name varchar(255),
    debtor_iban varchar(34),
    debtor_bban varchar(50),
    reconciliation_method varchar(50),
    FOREIGN KEY (transaction_id, tenant_id) REFERENCES transactions ON DELETE CASCADE
);

CREATE INDEX ON ztl_transactions (tenant_id);

CREATE TABLE warehouses (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    name varchar(160) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_archived bool DEFAULT false NOT NULL,
    inventory_managed_locally bool DEFAULT false NOT NULL,
    sales_location_id int,
    receiving_location_id int,
    PRIMARY KEY (id, tenant_id)
);

COMMENT ON COLUMN warehouses.inventory_managed_locally IS 'Explicit ReAI inventory authority; external catalog links cannot overwrite stock.';

CREATE INDEX ON warehouses (tenant_id);

CREATE TABLE inventory_opening_stocks (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    warehouse_id int NOT NULL,
    request jsonb NOT NULL,
    completed_at timestamptz NOT NULL,
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE TABLE inventory_receipts (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    warehouse_id int NOT NULL,
    completed_at timestamptz NOT NULL,
    request jsonb NOT NULL,
    snapshot jsonb NOT NULL,
    transaction_ids jsonb NOT NULL,
    PRIMARY KEY (id, tenant_id),
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE TABLE inventory_stock_counts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    warehouse_id int NOT NULL,
    completed_at timestamptz NOT NULL,
    request jsonb NOT NULL,
    snapshot jsonb NOT NULL,
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE TABLE inventory_transfers (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    source_warehouse_id int NOT NULL,
    destination_warehouse_id int NOT NULL,
    completed_at timestamptz NOT NULL,
    request jsonb NOT NULL,
    source_warehouse_name varchar(160) NOT NULL,
    destination_warehouse_name varchar(160) NOT NULL,
    FOREIGN KEY (destination_warehouse_id, tenant_id) REFERENCES warehouses,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (source_warehouse_id, tenant_id) REFERENCES warehouses,
    UNIQUE (tenant_id, request_id)
);

CREATE INDEX ON inventory_transfers (tenant_id, destination_warehouse_id, id DESC);

CREATE INDEX ON inventory_transfers (tenant_id, source_warehouse_id, id DESC);

CREATE TABLE inventory_transactions (
    occurred_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    variant_id int,
    warehouse_id int NOT NULL,
    quantity_change int DEFAULT 0 NOT NULL,
    transaction_type varchar(40) NOT NULL,
    unit_cost numeric(15,4) DEFAULT 0 NOT NULL,
    transfer_id int,
    zone_changes jsonb DEFAULT '[]' NOT NULL,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (product_id, tenant_id) REFERENCES products ON DELETE CASCADE,
    FOREIGN KEY (transfer_id, tenant_id) REFERENCES inventory_transfers(id, tenant_id),
    FOREIGN KEY (variant_id, product_id, tenant_id) REFERENCES product_variants(id, product_id, tenant_id) ON DELETE SET NULL (variant_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses ON DELETE CASCADE
);

COMMENT ON COLUMN inventory_transactions.zone_changes IS 'Immutable signed zone/shelf allocation snapshots. Unknown physical counts stay unknown after an authoritative stock issue.';

CREATE INDEX ON inventory_transactions (product_id);

CREATE INDEX ON inventory_transactions (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX ON inventory_transactions (tenant_id, transfer_id) WHERE transfer_id IS NOT NULL;

CREATE INDEX ON inventory_transactions (tenant_id, variant_id, warehouse_id) INCLUDE (quantity_change);

CREATE INDEX ON inventory_transactions (tenant_id, warehouse_id, product_id, variant_id) INCLUDE (quantity_change);

CREATE INDEX ON inventory_transactions (warehouse_id);

CREATE TABLE inventory_adjustment_requests (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    inventory_transaction_id int NOT NULL,
    request jsonb NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (inventory_transaction_id, tenant_id) REFERENCES inventory_transactions,
    UNIQUE (inventory_transaction_id, tenant_id),
    UNIQUE (tenant_id, request_id)
);

CREATE TABLE inventory_transfer_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    transfer_id int NOT NULL,
    variant_id int NOT NULL,
    quantity int NOT NULL CHECK (quantity >= 1 AND quantity <= 1000000),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (transfer_id, tenant_id) REFERENCES inventory_transfers(id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants
);

CREATE INDEX ON inventory_transfer_lines (tenant_id, transfer_id, id);

CREATE INDEX ON inventory_transfer_lines (tenant_id, variant_id, transfer_id);

CREATE TABLE kassasystem_cash_points (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    code varchar(50) NOT NULL CHECK (length(btrim(code)) > 0),
    name varchar(255) NOT NULL CHECK (length(btrim(name)) > 0),
    is_active bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    inventory_warehouse_id int,
    self_service_token uuid DEFAULT gen_random_uuid() NOT NULL,
    self_service_enabled bool DEFAULT false NOT NULL,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (inventory_warehouse_id, tenant_id) REFERENCES warehouses,
    UNIQUE (tenant_id, code)
);

CREATE UNIQUE INDEX ON kassasystem_cash_points (self_service_token);

CREATE TABLE kassasystem_journal_events (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    cash_point_id int NOT NULL,
    event_id varchar(100) NOT NULL CHECK (length(btrim(event_id)) > 0),
    sequence_number bigint NOT NULL CHECK (sequence_number > 0),
    occurred_at timestamptz NOT NULL,
    operator_id varchar(100) NOT NULL CHECK (length(btrim(operator_id)) > 0),
    receipt_number varchar(100) NOT NULL CHECK (length(btrim(receipt_number)) > 0),
    event_type varchar(32) NOT NULL,
    net_amount numeric(15,2) NOT NULL,
    vat_amount numeric(15,2) NOT NULL,
    gross_amount numeric(15,2) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    currency_code varchar(3),
    discount_public_id uuid,
    discount_code varchar(100),
    inventory_warehouse_id int,
    collect_later bool DEFAULT false NOT NULL,
    customer_id int,
    FOREIGN KEY (cash_point_id, tenant_id) REFERENCES kassasystem_cash_points(id, tenant_id) ON DELETE RESTRICT,
    CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (customer_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (inventory_warehouse_id, tenant_id) REFERENCES warehouses,
    UNIQUE (tenant_id, cash_point_id, receipt_number),
    UNIQUE (tenant_id, cash_point_id, sequence_number),
    UNIQUE (tenant_id, event_id)
);

CREATE INDEX ON kassasystem_journal_events (customer_id, tenant_id) WHERE customer_id IS NOT NULL;

CREATE INDEX ON kassasystem_journal_events (tenant_id, cash_point_id, occurred_at);

CREATE TABLE kassasystem_journal_event_postings (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    journal_event_id bigint NOT NULL,
    voucher_id bigint,
    voucher_reference varchar(100),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id) ON DELETE RESTRICT,
    UNIQUE (tenant_id, journal_event_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE RESTRICT
);

CREATE INDEX ON kassasystem_journal_event_postings (tenant_id, voucher_id) WHERE voucher_id IS NOT NULL;

CREATE TABLE kassasystem_journal_lines (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    journal_event_id bigint NOT NULL,
    row_number smallint NOT NULL CHECK (row_number > 0),
    product_text varchar(500) NOT NULL CHECK (length(btrim(product_text)) > 0),
    product_group varchar(100) NOT NULL CHECK (length(btrim(product_group)) > 0),
    revenue_account_code varchar(10) NOT NULL CHECK (length(btrim(revenue_account_code)) > 0),
    vat_code varchar(10) NOT NULL CHECK (length(btrim(vat_code)) > 0),
    quantity numeric(15,4) NOT NULL CHECK (quantity > 0),
    net_amount numeric(15,2) NOT NULL,
    vat_amount numeric(15,2) NOT NULL,
    gross_amount numeric(15,2) NOT NULL,
    discount_amount numeric(15,2) DEFAULT 0 NOT NULL CHECK (discount_amount >= 0),
    variant_id int,
    CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0),
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, journal_event_id, row_number)
);

CREATE TABLE kassasystem_journal_payments (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    journal_event_id bigint NOT NULL,
    row_number smallint NOT NULL CHECK (row_number > 0),
    method varchar(30) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    reference varchar(255) CHECK (reference IS NULL OR length(btrim(reference)) > 0),
    gift_card_id int,
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, journal_event_id, row_number)
);

CREATE TABLE kassasystem_pickups (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    journal_event_id bigint NOT NULL UNIQUE,
    status varchar(16) NOT NULL CHECK (status IN ('WAITING', 'DELIVERED', 'CANCELLED')),
    inventory_reference varchar(150),
    created_at timestamptz DEFAULT now() NOT NULL,
    created_by varchar(100) NOT NULL,
    delivered_at timestamptz,
    delivered_by varchar(100),
    cancelled_at timestamptz,
    cancelled_by varchar(100),
    CHECK (num_nonnulls(delivered_at, delivered_by) =
CASE
    WHEN status = 'DELIVERED' THEN 2
    ELSE 0
END),
    CONSTRAINT kassasystem_pickups_check1 CHECK (num_nonnulls(cancelled_at, cancelled_by) =
CASE
    WHEN status = 'CANCELLED' THEN 2
    ELSE 0
END),
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id)
);

CREATE TABLE kassasystem_sales (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    sale_reference_id varchar(100) NOT NULL CHECK (length(btrim(sale_reference_id)) > 0),
    cash_point_id int NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    paid_amount numeric(15,2) DEFAULT 0 NOT NULL,
    remaining_amount numeric(15,2) NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status varchar(30) NOT NULL,
    version_number bigint DEFAULT 0 NOT NULL CHECK (version_number >= 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamptz,
    customer_id int,
    inventory_warehouse_id int,
    collect_later bool DEFAULT false NOT NULL,
    FOREIGN KEY (cash_point_id, tenant_id) REFERENCES kassasystem_cash_points(id, tenant_id) ON DELETE RESTRICT,
    CHECK (total_amount > 0 AND paid_amount >= 0 AND remaining_amount >= 0 AND paid_amount + remaining_amount = total_amount),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers ON DELETE SET NULL (customer_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (inventory_warehouse_id, tenant_id) REFERENCES warehouses,
    UNIQUE (tenant_id, sale_reference_id)
);

CREATE INDEX ON kassasystem_sales (tenant_id, customer_id);

CREATE TABLE kassasystem_sale_gift_cards (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    sale_aggregate_id bigint NOT NULL,
    gift_card_id int NOT NULL,
    code varchar(32) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    created_at timestamptz DEFAULT now() NOT NULL,
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (sale_aggregate_id, tenant_id) REFERENCES kassasystem_sales(id, tenant_id),
    UNIQUE (tenant_id, sale_aggregate_id, gift_card_id)
);

COMMENT ON TABLE kassasystem_sale_gift_cards IS 'Gift cards presented for a terminal sale. The balance is drawn when the receipt is written, so an abandoned sale leaves the card untouched.';

CREATE TABLE kassasystem_terminals (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    cash_point_id int NOT NULL,
    display_name varchar(255) NOT NULL CHECK (length(btrim(display_name)) > 0),
    merchant_account varchar(100) NOT NULL CHECK (length(btrim(merchant_account)) > 0),
    device_id varchar(100) NOT NULL CHECK (length(btrim(device_id)) > 0),
    poi_id varchar(100) NOT NULL CHECK (length(btrim(poi_id)) > 0),
    is_active bool DEFAULT true NOT NULL,
    terminal_status varchar(30) DEFAULT 'UNKNOWN' NOT NULL,
    terminal_status_detail varchar(1000),
    terminal_status_changed_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mobile_device_id varchar(100),
    FOREIGN KEY (cash_point_id, tenant_id) REFERENCES kassasystem_cash_points(id, tenant_id) ON DELETE RESTRICT,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, device_id),
    UNIQUE (tenant_id, poi_id)
);

CREATE INDEX ON kassasystem_terminals (tenant_id, cash_point_id, is_active, display_name);

CREATE UNIQUE INDEX ON kassasystem_terminals (tenant_id, mobile_device_id) WHERE mobile_device_id IS NOT NULL;

CREATE TABLE kassasystem_terminal_payments (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    terminal_id int,
    cash_point_id int NOT NULL,
    journal_event_id bigint,
    original_payment_attempt_id bigint,
    original_receipt_number varchar(100),
    sale_id varchar(100) NOT NULL CHECK (length(btrim(sale_id)) > 0),
    service_id varchar(20) NOT NULL CHECK (length(btrim(service_id)) > 0),
    poi_id varchar(100) NOT NULL CHECK (length(btrim(poi_id)) > 0),
    merchant_account varchar(100) NOT NULL CHECK (length(btrim(merchant_account)) > 0),
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    sale_total_amount numeric(15,2) NOT NULL CHECK (sale_total_amount > 0),
    authorized_amount numeric(15,2),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    event_type varchar(30) NOT NULL CHECK (event_type IN ('SALE', 'RETURN', 'CORRECTION')),
    operator_id varchar(100) NOT NULL CHECK (length(btrim(operator_id)) > 0),
    status varchar(30) NOT NULL,
    transaction_id varchar(255),
    psp_reference varchar(100),
    tender_reference varchar(255),
    original_payment_tender_reference varchar(255),
    original_payment_psp_reference varchar(100),
    original_payment_date timestamptz,
    original_payment_terminal_id varchar(100),
    refusal_reason varchar(500),
    refund_settlement_outcome varchar(30) CHECK (refund_settlement_outcome IS NULL OR refund_settlement_outcome IN ('VALIDATION_PENDING', 'VALIDATION_ACCEPTED', 'VALIDATION_FAILED', 'FAILED', 'REVERSED', 'MANUALLY_CONFIRMED')),
    refund_reconciliation_status varchar(30) CHECK (refund_reconciliation_status IS NULL OR refund_reconciliation_status IN ('NOT_REQUIRED', 'MONITORING', 'REQUIRED', 'RESOLVED')),
    refund_settlement_occurred_at timestamptz,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamptz,
    sale_aggregate_id bigint,
    recovery_attempt_count int DEFAULT 0 NOT NULL CHECK (recovery_attempt_count >= 0),
    last_recovery_at timestamptz,
    next_recovery_at timestamptz,
    last_recovery_error text,
    recovery_lock_until timestamptz,
    mobile_request_reference varchar(100),
    tip_amount numeric(15,2) DEFAULT 0 NOT NULL,
    request_hash varchar(64),
    response_hash varchar(64),
    transaction_timestamp timestamptz,
    request_payload text,
    receipt_download_token varchar(43),
    receipt_download_expires_at timestamptz,
    payment_method varchar(30) DEFAULT 'CARD' NOT NULL,
    notification_device_id varchar(100),
    notification_seen_at timestamptz,
    notification_sent_at timestamptz,
    notification_next_attempt_at timestamptz DEFAULT now() NOT NULL,
    notification_attempts smallint DEFAULT 0 NOT NULL,
    merchant_reference text,
    receipt_email varchar(254),
    receipt_email_sent_at timestamptz,
    receipt_email_next_attempt_at timestamptz,
    receipt_email_attempts smallint DEFAULT 0 NOT NULL,
    discount_public_id uuid,
    payment_link_id varchar(100),
    payment_link_url text,
    payment_link_expires_at timestamptz,
    discount_code varchar(100),
    refund_kind varchar(20) DEFAULT 'GOODS' NOT NULL,
    refund_reason varchar(500),
    self_service_token uuid,
    checkout_locale varchar(5),
    checkout_session_id varchar(100),
    checkout_session_data text,
    checkout_session_expires_at timestamptz,
    FOREIGN KEY (cash_point_id, tenant_id) REFERENCES kassasystem_cash_points(id, tenant_id) ON DELETE RESTRICT,
    CHECK (terminal_id IS NOT NULL <> (self_service_token IS NOT NULL)),
    CONSTRAINT kassasystem_terminal_payments_check1 CHECK (amount <= sale_total_amount),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (original_payment_attempt_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (sale_aggregate_id, tenant_id) REFERENCES kassasystem_sales(id, tenant_id) ON DELETE RESTRICT,
    UNIQUE (tenant_id, terminal_id, service_id),
    FOREIGN KEY (terminal_id, tenant_id) REFERENCES kassasystem_terminals(id, tenant_id) ON DELETE RESTRICT
);

CREATE INDEX ON kassasystem_terminal_payments (merchant_account, merchant_reference) WHERE event_type = 'SALE' AND merchant_reference IS NOT NULL;

CREATE INDEX ON kassasystem_terminal_payments (notification_next_attempt_at) WHERE notification_device_id IS NOT NULL AND notification_seen_at IS NULL AND notification_sent_at IS NULL AND notification_attempts < 12 AND status IN ('REFUSED', 'CANCELLED');

CREATE UNIQUE INDEX ON kassasystem_terminal_payments (payment_link_id) WHERE payment_link_id IS NOT NULL;

CREATE UNIQUE INDEX ON kassasystem_terminal_payments (receipt_download_token) WHERE receipt_download_token IS NOT NULL;

CREATE INDEX ON kassasystem_terminal_payments (receipt_email_next_attempt_at, id) WHERE receipt_email IS NOT NULL AND receipt_email_sent_at IS NULL;

CREATE INDEX ON kassasystem_terminal_payments (request_hash) WHERE request_hash IS NOT NULL;

CREATE UNIQUE INDEX ON kassasystem_terminal_payments (self_service_token);

CREATE INDEX ON kassasystem_terminal_payments (tenant_id, journal_event_id) WHERE journal_event_id IS NOT NULL;

CREATE INDEX ON kassasystem_terminal_payments (tenant_id, operator_id, notification_device_id, id) WHERE notification_device_id IS NOT NULL AND notification_seen_at IS NULL AND status IN ('REFUSED', 'CANCELLED');

CREATE INDEX ON kassasystem_terminal_payments (tenant_id, sale_aggregate_id, id);

CREATE INDEX ON kassasystem_terminal_payments (tenant_id, terminal_id, created_at DESC);

CREATE INDEX ON kassasystem_terminal_payments (tenant_id, terminal_id, mobile_request_reference) WHERE mobile_request_reference IS NOT NULL;

CREATE UNIQUE INDEX kassasystem_terminal_payments_tenant_id_terminal_id_mobile_idx1 ON kassasystem_terminal_payments (tenant_id, terminal_id, mobile_request_reference) WHERE event_type = 'SALE' AND mobile_request_reference IS NOT NULL;

CREATE TABLE kassasystem_return_credits (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reference varchar(100) NOT NULL,
    cash_point_id int NOT NULL,
    sequence_number bigint NOT NULL,
    original_payment_attempt_id bigint,
    journal_event_id bigint NOT NULL,
    gift_card_id int,
    lines jsonb NOT NULL,
    refund_kind varchar(20) DEFAULT 'GOODS' NOT NULL,
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id),
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id),
    FOREIGN KEY (original_payment_attempt_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id),
    FOREIGN KEY (tenant_id, cash_point_id, sequence_number) REFERENCES kassasystem_journal_events(tenant_id, cash_point_id, sequence_number),
    UNIQUE (tenant_id, reference)
);

CREATE INDEX ON kassasystem_return_credits (tenant_id, original_payment_attempt_id);

CREATE TABLE kassasystem_terminal_payment_lines (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    terminal_payment_id bigint NOT NULL,
    product_id int CHECK (product_id IS NULL OR product_id > 0),
    row_number smallint NOT NULL CHECK (row_number > 0),
    source_row_number smallint NOT NULL CHECK (source_row_number > 0),
    product_text varchar(500) NOT NULL CHECK (length(btrim(product_text)) > 0),
    product_group varchar(100) NOT NULL CHECK (length(btrim(product_group)) > 0),
    revenue_account_code varchar(10) NOT NULL CHECK (length(btrim(revenue_account_code)) > 0),
    vat_code varchar(10) NOT NULL CHECK (length(btrim(vat_code)) > 0),
    quantity numeric(15,4) NOT NULL CHECK (quantity > 0),
    net_amount numeric(15,2) NOT NULL,
    vat_amount numeric(15,2) NOT NULL,
    gross_amount numeric(15,2) NOT NULL,
    discount_amount numeric(15,2) DEFAULT 0 NOT NULL CHECK (discount_amount >= 0),
    UNIQUE (tenant_id, terminal_payment_id, row_number),
    FOREIGN KEY (terminal_payment_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id) ON DELETE CASCADE,
    CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0),
    FOREIGN KEY (product_id, tenant_id) REFERENCES product_variants ON DELETE SET NULL (product_id)
);

CREATE INDEX ON kassasystem_terminal_payment_lines (product_id, tenant_id) WHERE product_id IS NOT NULL;

CREATE TABLE kassasystem_terminal_payment_receipts (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    terminal_payment_id bigint NOT NULL,
    receipt_type varchar(30) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    content text NOT NULL,
    CONSTRAINT kassasystem_terminal_payment__tenant_id_terminal_payment_i_key1 UNIQUE (tenant_id, terminal_payment_id, receipt_type),
    CONSTRAINT kassasystem_terminal_payment_terminal_payment_id_tenant_i_fkey1 FOREIGN KEY (terminal_payment_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE kassasystem_terminal_payment_state_events (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    terminal_payment_id bigint NOT NULL,
    from_status varchar(30) CHECK (from_status IS NULL OR from_status IN ('PENDING', 'ACCEPTED', 'PARTIALLY_AUTHORIZED', 'AUTHORIZED', 'REFUSED', 'CANCELLED', 'TIMEOUT', 'ERROR')),
    to_status varchar(30) NOT NULL,
    source varchar(50) NOT NULL CHECK (length(btrim(source)) > 0),
    response_hash varchar(64) NOT NULL CHECK (response_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT kassasystem_terminal_payment__tenant_id_terminal_payment_i_key2 UNIQUE (tenant_id, terminal_payment_id, response_hash),
    CONSTRAINT kassasystem_terminal_payment_terminal_payment_id_tenant_i_fkey2 FOREIGN KEY (terminal_payment_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON kassasystem_terminal_payment_state_events (tenant_id, terminal_payment_id, created_at DESC);

CREATE TABLE purchase_order_shipments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reference varchar(160) NOT NULL CHECK (btrim(reference) <> ''),
    status varchar(20) NOT NULL,
    carrier_name varchar(255),
    shipment_method varchar(20) CHECK (shipment_method IS NULL OR shipment_method IN ('SEA', 'AIR', 'ROAD', 'RAIL', 'COURIER', 'MULTIMODAL', 'OTHER')),
    destination_warehouse_id int,
    destination_warehouse_name varchar(255),
    expected_ready_date date,
    departure_date date,
    estimated_arrival_date date,
    actual_arrival_date date,
    notes text CHECK (notes IS NULL OR char_length(notes) <= 10000),
    version_number bigint NOT NULL CHECK (version_number >= 0),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    freight_supplier_id int,
    CHECK ((departure_date IS NULL OR estimated_arrival_date IS NULL OR estimated_arrival_date >= departure_date) AND (departure_date IS NULL OR actual_arrival_date IS NULL OR actual_arrival_date >= departure_date)),
    FOREIGN KEY (destination_warehouse_id, tenant_id) REFERENCES warehouses,
    FOREIGN KEY (freight_supplier_id, tenant_id) REFERENCES suppliers,
    UNIQUE (id, tenant_id)
);

CREATE INDEX ON purchase_order_shipments (tenant_id, destination_warehouse_id) WHERE destination_warehouse_id IS NOT NULL;

CREATE INDEX ON purchase_order_shipments (tenant_id, freight_supplier_id) WHERE freight_supplier_id IS NOT NULL;

CREATE UNIQUE INDEX ON purchase_order_shipments (tenant_id, lower(reference));

CREATE INDEX ON purchase_order_shipments (tenant_id, status, estimated_arrival_date, id DESC);

CREATE TABLE purchase_order_shipment_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    shipment_id int NOT NULL,
    attachment_id int NOT NULL,
    document_type varchar(30) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, shipment_id, attachment_id),
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE RESTRICT,
    FOREIGN KEY (shipment_id, tenant_id) REFERENCES purchase_order_shipments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON purchase_order_shipment_attachments (tenant_id, shipment_id, created_at DESC);

CREATE TABLE purchase_order_shipment_tracking (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    shipment_id int NOT NULL,
    row_number smallint NOT NULL CHECK (row_number > 0),
    reference varchar(255) NOT NULL CHECK (btrim(reference) <> ''),
    url varchar(2048),
    UNIQUE (tenant_id, shipment_id, row_number) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (shipment_id, tenant_id) REFERENCES purchase_order_shipments(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE shopify_installation (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int REFERENCES tenants ON DELETE CASCADE,
    shop_domain text NOT NULL,
    access_token text NOT NULL,
    scopes text NOT NULL,
    installed_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    status text DEFAULT 'ACTIVE' NOT NULL,
    claim_token text,
    claim_token_expires_at timestamptz,
    auto_sync_enabled bool DEFAULT true NOT NULL,
    ignored_order_tags text DEFAULT '' NOT NULL,
    shop_gid text,
    sync_product_images_enabled bool DEFAULT false NOT NULL,
    authorized_at timestamptz,
    auto_order_sync_enabled bool DEFAULT false NOT NULL,
    sync_translations_enabled bool DEFAULT false NOT NULL,
    auto_sales_sync_enabled bool DEFAULT true NOT NULL,
    auto_customer_sync_enabled bool DEFAULT false NOT NULL,
    inventory_warehouse_id int,
    pos_order_sync_enabled bool DEFAULT false NOT NULL,
    pos_order_sync_from date,
    refresh_token text,
    access_token_expires_at timestamptz,
    refresh_token_expires_at timestamptz,
    auto_sales_sync_from date CHECK (EXTRACT(day FROM auto_sales_sync_from) = 1),
    FOREIGN KEY (inventory_warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE UNIQUE INDEX ON shopify_installation (claim_token) WHERE claim_token IS NOT NULL;

CREATE UNIQUE INDEX ON shopify_installation (id, tenant_id);

CREATE UNIQUE INDEX ON shopify_installation (shop_domain);

CREATE TABLE shopify_customer_sync (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    installation_id int NOT NULL,
    shopify_customer_id bigint NOT NULL,
    reai_customer_id int NOT NULL,
    synced_at timestamptz NOT NULL,
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (reai_customer_id, tenant_id) REFERENCES customers
);

CREATE INDEX ON shopify_customer_sync (tenant_id, installation_id, reai_customer_id);

CREATE UNIQUE INDEX ON shopify_customer_sync (tenant_id, installation_id, shopify_customer_id);

CREATE TABLE shopify_pos_order_sync (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    installation_id int NOT NULL,
    journal_event_id bigint NOT NULL,
    shopify_order_gid text,
    attempted_at timestamptz DEFAULT now() NOT NULL,
    UNIQUE (installation_id, journal_event_id),
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id)
);

CREATE TABLE shopify_product_sync (
    synced_at timestamptz NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    reai_product_id int NOT NULL,
    installation_id int NOT NULL,
    shopify_product_id bigint NOT NULL,
    product_title text NOT NULL,
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (reai_product_id, tenant_id) REFERENCES products
);

CREATE INDEX ON shopify_product_sync (tenant_id, installation_id, product_title, id);

CREATE INDEX ON shopify_product_sync (tenant_id, installation_id, reai_product_id);

CREATE UNIQUE INDEX ON shopify_product_sync (tenant_id, installation_id, shopify_product_id);

CREATE TABLE shopify_sales_record (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    installation_id int NOT NULL,
    year smallint NOT NULL,
    month smallint NOT NULL,
    fetched_at timestamptz NOT NULL,
    voucher_id bigint,
    voucher_number text,
    voucher_date date,
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, installation_id, year, month),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers ON DELETE CASCADE
);

CREATE TABLE shopify_sync_issue (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    installation_id int NOT NULL,
    shopify_product_id bigint NOT NULL,
    shopify_product_title text NOT NULL,
    reason text NOT NULL,
    occurred_at timestamptz NOT NULL,
    duplicate_reai_product_id int,
    FOREIGN KEY (duplicate_reai_product_id, tenant_id) REFERENCES products ON DELETE SET NULL (duplicate_reai_product_id),
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, installation_id, shopify_product_id)
);

CREATE INDEX ON shopify_sync_issue (tenant_id, installation_id, occurred_at DESC, id DESC);

CREATE TABLE shopify_warehouse_locations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    installation_id int NOT NULL,
    location_gid varchar(100) NOT NULL,
    warehouse_id int NOT NULL,
    UNIQUE (installation_id, location_gid),
    FOREIGN KEY (installation_id, tenant_id) REFERENCES shopify_installation(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE TABLE warehouse_zones (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    warehouse_id int NOT NULL,
    name varchar(80) NOT NULL CHECK (length(TRIM(BOTH FROM name)) > 0),
    description varchar(500),
    is_archived bool DEFAULT false NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    replenishment_source_zone_id int,
    CHECK (replenishment_source_zone_id IS NULL OR replenishment_source_zone_id <> id),
    UNIQUE (id, warehouse_id, tenant_id),
    FOREIGN KEY (replenishment_source_zone_id, warehouse_id, tenant_id) REFERENCES warehouse_zones(id, warehouse_id, tenant_id) ON DELETE SET NULL (replenishment_source_zone_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses ON DELETE CASCADE
);

COMMENT ON COLUMN warehouse_zones.replenishment_source_zone_id IS 'Optional default source zone for replenishing this destination. Must belong to the same warehouse and tenant; no stock is moved by configuration.';

CREATE UNIQUE INDEX ON warehouse_zones (tenant_id, warehouse_id, lower(name));

CREATE TABLE warehouse_locations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    warehouse_id int NOT NULL,
    name varchar(120) NOT NULL CHECK (length(TRIM(BOTH FROM name)) > 0),
    row_number int,
    section_number int,
    level_number int,
    description varchar(500),
    zone_id int,
    custom_name varchar(80),
    scan_id uuid DEFAULT gen_random_uuid() NOT NULL,
    CHECK ((row_number IS NULL OR row_number >= 1 AND row_number <= 999) AND (section_number IS NULL OR section_number >= 1 AND section_number <= 999) AND (level_number IS NULL OR level_number >= 1 AND level_number <= 999)),
    UNIQUE (id, warehouse_id, tenant_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses ON DELETE CASCADE,
    FOREIGN KEY (zone_id, warehouse_id, tenant_id) REFERENCES warehouse_zones(id, warehouse_id, tenant_id)
);

COMMENT ON COLUMN warehouse_locations.zone_id IS 'Optional operational grouping inside the same warehouse. Grouping does not move stock or make shelf observations into booked balances.';

CREATE UNIQUE INDEX ON warehouse_locations (scan_id);

CREATE INDEX ON warehouse_locations (tenant_id, warehouse_id, zone_id);

CREATE UNIQUE INDEX ON warehouse_locations (tenant_id, warehouse_id, zone_id, lower(name)) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX ON warehouse_locations (tenant_id, warehouse_id, zone_id, row_number, section_number, level_number) NULLS NOT DISTINCT WHERE row_number IS NOT NULL OR section_number IS NOT NULL OR level_number IS NOT NULL;

CREATE TABLE purchase_orders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    number varchar(160) NOT NULL CHECK (btrim(number) <> ''),
    status varchar(20) NOT NULL,
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    order_date date,
    expected_date date,
    supplier_name varchar(255),
    warehouse_name varchar(255),
    total_cost numeric(15,4) CHECK (total_cost IS NULL OR total_cost >= 0),
    external_source varchar(80),
    external_id varchar(255),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    supplier_id int,
    warehouse_id int,
    version_number bigint NOT NULL CHECK (version_number >= 0),
    archived bool NOT NULL,
    supplier_progress varchar(20) CHECK (supplier_progress IS NULL OR supplier_progress IN ('NOT_STARTED', 'IN_PROGRESS', 'READY')),
    expected_ready_date date,
    notes text CHECK (notes IS NULL OR char_length(notes) <= 10000),
    supplier_comment text,
    last_emailed_at timestamptz,
    last_emailed_to text,
    default_location_id int,
    CHECK (external_source IS NULL = (external_id IS NULL)),
    FOREIGN KEY (default_location_id, warehouse_id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers,
    UNIQUE (tenant_id, external_source, external_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE INDEX ON purchase_orders (tenant_id, archived, status, expected_date, id DESC);

CREATE UNIQUE INDEX ON purchase_orders (tenant_id, lower(number)) WHERE external_source IS NULL;

CREATE INDEX ON purchase_orders (tenant_id, status, expected_date, id DESC);

CREATE INDEX ON purchase_orders (tenant_id, supplier_id) WHERE supplier_id IS NOT NULL;

CREATE INDEX ON purchase_orders (tenant_id, supplier_progress, expected_ready_date, id DESC) WHERE archived = false;

CREATE INDEX ON purchase_orders (tenant_id, warehouse_id) WHERE warehouse_id IS NOT NULL;

CREATE TABLE purchase_order_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    purchase_order_id int NOT NULL,
    attachment_id int NOT NULL,
    document_type varchar(30) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE RESTRICT,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (purchase_order_id, tenant_id) REFERENCES purchase_orders(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, purchase_order_id, attachment_id)
);

CREATE INDEX ON purchase_order_attachments (tenant_id, purchase_order_id, created_at DESC);

CREATE TABLE purchase_order_lines (
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    purchase_order_id int NOT NULL,
    ordered_quantity int NOT NULL CHECK (ordered_quantity > 0),
    received_quantity int DEFAULT 0 NOT NULL CHECK (received_quantity >= 0),
    product_variant_id int,
    row_number smallint NOT NULL,
    title varchar(500) NOT NULL CHECK (btrim(title) <> ''),
    sku varchar(160) NOT NULL CHECK (btrim(sku) <> ''),
    barcode varchar(160),
    unit_cost numeric(15,4) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    description varchar(2000),
    PRIMARY KEY (id, tenant_id),
    UNIQUE (id, purchase_order_id, tenant_id),
    FOREIGN KEY (product_variant_id, tenant_id) REFERENCES product_variants,
    FOREIGN KEY (purchase_order_id, tenant_id) REFERENCES purchase_orders(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, purchase_order_id, row_number) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ON purchase_order_lines (tenant_id, product_variant_id) WHERE product_variant_id IS NOT NULL;

CREATE TABLE purchase_order_receipts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    purchase_order_id int NOT NULL,
    purchase_order_number varchar(160) NOT NULL,
    warehouse_id int,
    warehouse_name varchar(160),
    received_on date NOT NULL,
    received_by varchar(255) NOT NULL,
    received_by_user_id int REFERENCES users ON DELETE SET NULL,
    request_key uuid NOT NULL,
    reversed_receipt_id int,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    zone_selection jsonb DEFAULT '{}' NOT NULL,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (purchase_order_id, tenant_id) REFERENCES purchase_orders(id, tenant_id),
    FOREIGN KEY (reversed_receipt_id, tenant_id) REFERENCES purchase_order_receipts(id, tenant_id),
    UNIQUE (reversed_receipt_id, tenant_id),
    UNIQUE (tenant_id, request_key),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

CREATE INDEX ON purchase_order_receipts (tenant_id, purchase_order_id, id DESC);

CREATE TABLE purchase_order_receipt_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    receipt_id int NOT NULL,
    purchase_order_line_id int NOT NULL,
    product_variant_id int NOT NULL,
    title varchar(500) NOT NULL,
    sku varchar(160) NOT NULL,
    quantity int NOT NULL CHECK (quantity <> 0),
    stock_tracked bool NOT NULL,
    unit_cost numeric(15,4),
    currency varchar(3) NOT NULL,
    inventory_transaction_id int,
    CHECK (stock_tracked OR inventory_transaction_id IS NULL),
    FOREIGN KEY (inventory_transaction_id, tenant_id) REFERENCES inventory_transactions,
    UNIQUE (inventory_transaction_id, tenant_id),
    FOREIGN KEY (product_variant_id, tenant_id) REFERENCES product_variants,
    FOREIGN KEY (purchase_order_line_id, tenant_id) REFERENCES purchase_order_lines,
    UNIQUE (receipt_id, purchase_order_line_id, tenant_id),
    FOREIGN KEY (receipt_id, tenant_id) REFERENCES purchase_order_receipts(id, tenant_id)
);

CREATE INDEX ON purchase_order_receipt_lines (tenant_id, purchase_order_line_id);

CREATE TABLE purchase_order_shipment_orders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    shipment_id int NOT NULL,
    purchase_order_id int NOT NULL,
    UNIQUE (shipment_id, purchase_order_id, tenant_id),
    UNIQUE (tenant_id, shipment_id, purchase_order_id),
    FOREIGN KEY (purchase_order_id, tenant_id) REFERENCES purchase_orders(id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (shipment_id, tenant_id) REFERENCES purchase_order_shipments(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON purchase_order_shipment_orders (tenant_id, purchase_order_id, shipment_id);

CREATE TABLE purchase_order_shipment_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    shipment_id int NOT NULL,
    purchase_order_id int NOT NULL,
    purchase_order_line_id int NOT NULL,
    quantity int NOT NULL CHECK (quantity > 0),
    FOREIGN KEY (purchase_order_line_id, purchase_order_id, tenant_id) REFERENCES purchase_order_lines(id, purchase_order_id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (shipment_id, purchase_order_id, tenant_id) REFERENCES purchase_order_shipment_orders(shipment_id, purchase_order_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, shipment_id, purchase_order_line_id)
);

CREATE INDEX ON purchase_order_shipment_allocations (tenant_id, purchase_order_line_id, shipment_id);

CREATE TABLE purchase_order_supplier_invoices (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    purchase_order_id int NOT NULL,
    supplier_invoice_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (supplier_invoice_id, tenant_id) REFERENCES supplier_invoice ON DELETE RESTRICT,
    UNIQUE (tenant_id, purchase_order_id, supplier_invoice_id),
    FOREIGN KEY (purchase_order_id, tenant_id) REFERENCES purchase_orders(id, tenant_id) ON DELETE RESTRICT
);

CREATE INDEX ON purchase_order_supplier_invoices (tenant_id, supplier_invoice_id, purchase_order_id);

CREATE TABLE stock_targets (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    warehouse_id int NOT NULL,
    zone_id int,
    location_id int,
    variant_id int NOT NULL,
    quantity int NOT NULL CHECK (quantity >= 0 AND quantity <= 1000000),
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (num_nonnulls(zone_id, location_id) <= 1),
    FOREIGN KEY (location_id, warehouse_id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id) ON DELETE CASCADE,
    UNIQUE NULLS NOT DISTINCT (tenant_id, warehouse_id, zone_id, location_id, variant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE,
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses ON DELETE CASCADE,
    FOREIGN KEY (zone_id, warehouse_id, tenant_id) REFERENCES warehouse_zones(id, warehouse_id, tenant_id) ON DELETE CASCADE
);

COMMENT ON TABLE stock_targets IS 'Desired physical stock per variant and scope. Warehouse targets include child stock; targets at different levels are not additive. Missing row means unconfigured, quantity zero is explicit.';

CREATE INDEX ON stock_targets (tenant_id, variant_id);

CREATE TABLE warehouse_location_items (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    warehouse_id int NOT NULL,
    location_id int NOT NULL,
    variant_id int NOT NULL,
    quantity int CHECK (quantity <= 1000000),
    quantity_updated_at timestamptz,
    FOREIGN KEY (location_id, warehouse_id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE
);

COMMENT ON COLUMN warehouse_location_items.quantity IS 'Physical stock held at this location. Negative quantities retain oversold deficits; physical counts must be non-negative.';

CREATE UNIQUE INDEX ON warehouse_location_items (tenant_id, location_id, variant_id);

CREATE INDEX ON warehouse_location_items (tenant_id, warehouse_id, variant_id);

CREATE TABLE zone_replenishments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    warehouse_id int NOT NULL,
    source_zone_id int NOT NULL,
    destination_zone_id int NOT NULL,
    completed_at timestamptz NOT NULL,
    request jsonb NOT NULL,
    result jsonb NOT NULL,
    CHECK (source_zone_id <> destination_zone_id),
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses
);

COMMENT ON TABLE zone_replenishments IS 'Atomic, idempotent confirmations of internal zone replenishment. Immutable request and result snapshots preserve source/destination names. No warehouse inventory transactions or in-transit state.';

CREATE TABLE orders (
    source_updated_at timestamptz,
    imported_at timestamptz,
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    date date NOT NULL,
    customer_id int,
    invoice_issue_date date,
    project_id int,
    delivery_address_id int,
    delivery_date date,
    days_until_due smallint NOT NULL,
    send_ehf bool DEFAULT false NOT NULL,
    payment_gateway_managed bool DEFAULT false NOT NULL,
    number varchar(64) NOT NULL,
    currency_code varchar(3) NOT NULL,
    invoice_comment text,
    invoice_email varchar(100) CHECK (invoice_email IS NULL OR invoice_email = lower(invoice_email)),
    internal_comment text,
    our_reference varchar(255),
    buyer_reference varchar(255),
    external_reference varchar(100),
    source varchar(32) DEFAULT 'REAI' NOT NULL,
    source_instance varchar(255),
    external_id varchar(255),
    external_number varchar(255),
    lifecycle varchar(32) DEFAULT 'DRAFT' NOT NULL,
    invoice_handling varchar(32) DEFAULT 'REAI' NOT NULL,
    external_financial_status varchar(64),
    external_fulfillment_status varchar(64),
    external_customer_id varchar(255),
    customer_name varchar(255),
    customer_email varchar(320),
    customer_number varchar(50),
    purchase_order_reference varchar(100),
    locally_delivered_at timestamptz,
    locally_delivered_by text,
    seller_employee_id int,
    invoice_email_override bool DEFAULT false NOT NULL,
    source_vat_review jsonb,
    exchange_return_id int,
    exchange_payment_pending bool DEFAULT false NOT NULL,
    reply_to_email varchar(254),
    ordered_at timestamptz,
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (delivery_address_id, tenant_id) REFERENCES addresses,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    FOREIGN KEY (seller_employee_id, tenant_id) REFERENCES employees ON DELETE SET NULL (seller_employee_id),
    UNIQUE (tenant_id, number)
);

CREATE INDEX ON orders (customer_id, tenant_id, source, source_instance) INCLUDE (external_customer_id);

CREATE INDEX ON orders (delivery_address_id) WHERE delivery_address_id IS NOT NULL;

CREATE INDEX ON orders (external_customer_id, tenant_id, source, source_instance) INCLUDE (customer_id);

CREATE INDEX ON orders (project_id);

CREATE UNIQUE INDEX ON orders (tenant_id, exchange_return_id) WHERE exchange_return_id IS NOT NULL;

CREATE UNIQUE INDEX ON orders (tenant_id, external_reference) WHERE external_reference IS NOT NULL;

CREATE INDEX ON orders (tenant_id, lifecycle, date DESC);

CREATE INDEX ON orders (tenant_id, source, date DESC, id DESC);

CREATE UNIQUE INDEX ON orders (tenant_id, source, source_instance, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE inventory_order_fulfillments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    order_id int NOT NULL,
    reference varchar(150) NOT NULL,
    items jsonb NOT NULL,
    completed_at timestamptz NOT NULL,
    sources jsonb DEFAULT '{}' NOT NULL,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders,
    UNIQUE (tenant_id, reference)
);

CREATE TABLE invoice_ar_source_orders (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    order_id int NOT NULL,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ON invoice_ar_source_orders (tenant_id, invoice_id, order_id);

CREATE CONSTRAINT TRIGGER trg_invoice_ar_source_orders_ck AFTER INSERT OR DELETE OR UPDATE OF invoice_id, order_id ON invoice_ar_source_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_invoice_ar_source_order_change();

CREATE TABLE offers (
    id int GENERATED BY DEFAULT AS IDENTITY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    number varchar(64) NOT NULL,
    days_until_due smallint NOT NULL,
    date date NOT NULL,
    currency_code varchar(3) NOT NULL,
    customer_id int NOT NULL,
    issue_date date,
    comment text,
    email varchar(100) CHECK (email IS NULL OR email = lower(email)) CONSTRAINT offers_email_check1 CHECK (email IS NULL OR length(btrim(email)) > 0),
    project_id int,
    delivery_address_id int,
    internal_comment text,
    delivery_date date,
    customer_number varchar(50),
    purchase_order_reference varchar(100),
    converted_order_id int,
    reply_to_email varchar(254),
    PRIMARY KEY (id, tenant_id),
    FOREIGN KEY (converted_order_id, tenant_id) REFERENCES orders,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (delivery_address_id, tenant_id) REFERENCES addresses,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects,
    UNIQUE (tenant_id, number)
);

CREATE INDEX ON offers (converted_order_id, tenant_id) WHERE converted_order_id IS NOT NULL;

CREATE INDEX ON offers (delivery_address_id) WHERE delivery_address_id IS NOT NULL;

CREATE INDEX ON offers (project_id);

CREATE TABLE offer_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    row_number smallint NOT NULL,
    offer_id int NOT NULL,
    item_name varchar(255) NOT NULL,
    quantity numeric(10,2) NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    discount smallint,
    vat_code varchar(5) NOT NULL,
    variant_id int,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    comment text,
    group_heading bool DEFAULT false NOT NULL,
    sort_order int,
    FOREIGN KEY (offer_id, tenant_id) REFERENCES offers ON DELETE CASCADE,
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE SET NULL (variant_id)
);

CREATE INDEX ON offer_lines (offer_id);

CREATE TABLE order_confirmation_email_history (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    order_id int NOT NULL,
    to_email text NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE CASCADE
);

CREATE INDEX ON order_confirmation_email_history (tenant_id, order_id, created_at DESC);

CREATE TABLE order_invoice_attachments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id int NOT NULL,
    attachment_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, tenant_id) REFERENCES attachments ON DELETE CASCADE,
    UNIQUE (order_id, attachment_id, tenant_id),
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE CASCADE
);

CREATE INDEX ON order_invoice_attachments (tenant_id);

CREATE TABLE order_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id int NOT NULL,
    variant_id int,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    accrual_period date,
    row_number smallint NOT NULL CHECK (row_number >= 0),
    discount smallint,
    accrual_period_count smallint DEFAULT 1 NOT NULL,
    accrual_enabled bool DEFAULT false NOT NULL,
    item_name varchar(255) NOT NULL,
    quantity numeric(10,2) NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    vat_code varchar(5) NOT NULL,
    cost_price numeric(10,2) DEFAULT 0.00 NOT NULL,
    comment text,
    external_id varchar(255),
    sku varchar(255),
    variant_title varchar(255),
    external_product_id varchar(255),
    source_quantity numeric,
    source_current_quantity numeric,
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    discount_amount numeric(14,2),
    group_heading bool DEFAULT false NOT NULL,
    sort_order int,
    locally_delivered_quantity numeric DEFAULT 0 NOT NULL,
    goods_amount_including_vat numeric(14,2),
    UNIQUE (order_id, row_number) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE CASCADE,
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE SET NULL (variant_id)
);

CREATE UNIQUE INDEX ON order_lines (id, order_id, tenant_id);

CREATE UNIQUE INDEX ON order_lines (tenant_id, order_id, external_id) WHERE external_id IS NOT NULL;

CREATE INDEX ON order_lines (variant_id, tenant_id) WHERE variant_id IS NOT NULL;

CREATE TABLE order_pick_sources (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    order_id int NOT NULL,
    variant_id int NOT NULL,
    warehouse_id int NOT NULL,
    location_id int NOT NULL,
    FOREIGN KEY (location_id, warehouse_id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE CASCADE,
    UNIQUE (order_id, variant_id, tenant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE CASCADE
);

CREATE TABLE product_checkouts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    product_id int NOT NULL,
    variant_id int NOT NULL,
    product_title varchar(255) NOT NULL,
    variant_sku varchar(100) NOT NULL,
    variant_options jsonb DEFAULT '{}' NOT NULL,
    quantity int NOT NULL CHECK (quantity > 0 AND quantity <= 99),
    unit_price numeric(15,2) NOT NULL,
    vat_code varchar(10) NOT NULL,
    vat_rate numeric(5,2) NOT NULL,
    net_amount numeric(15,2) NOT NULL,
    vat_amount numeric(15,2) NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'NOK'),
    email varchar(255) NOT NULL CHECK (email = lower(email)),
    customer_snapshot jsonb NOT NULL,
    matched_customer_id int,
    fulfilled_customer_id int,
    fulfilled_order_id int,
    fulfilled_invoice_id int,
    public_token varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    anonymized_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CHECK (unit_price >= 0 AND net_amount >= 0 AND vat_amount >= 0 AND total_amount > 0),
    FOREIGN KEY (fulfilled_customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (fulfilled_invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (fulfilled_order_id, tenant_id) REFERENCES orders,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (matched_customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (product_id, tenant_id) REFERENCES products,
    FOREIGN KEY (variant_id, product_id, tenant_id) REFERENCES product_variants(id, product_id, tenant_id)
);

CREATE INDEX ON product_checkouts (expires_at) WHERE anonymized_at IS NULL AND fulfilled_order_id IS NULL;

CREATE TABLE sales_returns (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    order_id int,
    cash_point_id int,
    sequence_number bigint,
    source_number varchar(100) NOT NULL,
    currency char(3) NOT NULL,
    resolution varchar(20) NOT NULL CHECK (resolution IN ('REFUND', 'EXCHANGE', 'GIFT_CARD', 'RECEIVE_ONLY')),
    received_at timestamptz NOT NULL,
    received_by varchar(255) NOT NULL,
    request jsonb NOT NULL,
    lines jsonb NOT NULL CHECK (((jsonb_typeof(lines) = 'array'::text) AND ((jsonb_array_length(lines) >= 1) AND (jsonb_array_length(lines) <= 100)))),
    warehouse_id int,
    zone_id int,
    inventory_receipt_id int,
    warehouse_name varchar(255),
    zone_name varchar(255),
    refund_state varchar(30) DEFAULT 'NOT_REQUESTED' NOT NULL CHECK (refund_state IN ('NOT_REQUESTED', 'REQUESTED', 'CONFIRMED', 'REVIEW_REQUIRED', 'EXCHANGE_CREDIT', 'CREDIT_NOTE')),
    refund_payment_attempt_id bigint,
    refund_journal_event_id bigint,
    credit_note_id int,
    replacement_order_id int,
    replacement_reference varchar(100),
    exchange_credit_code varchar(32),
    replacement_invoice_id int,
    replacement_payment_id int,
    replacement_request jsonb,
    CHECK (order_id IS NOT NULL AND cash_point_id IS NULL AND sequence_number IS NULL OR order_id IS NULL AND cash_point_id IS NOT NULL AND sequence_number IS NOT NULL AND sequence_number > 0),
    CONSTRAINT sales_returns_check1 CHECK (zone_id IS NULL OR warehouse_id IS NOT NULL),
    FOREIGN KEY (credit_note_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (exchange_credit_code, tenant_id) REFERENCES gift_cards(code, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (inventory_receipt_id, tenant_id) REFERENCES inventory_receipts,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders,
    FOREIGN KEY (refund_journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id),
    FOREIGN KEY (refund_payment_attempt_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id),
    FOREIGN KEY (replacement_invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (replacement_order_id, tenant_id) REFERENCES orders,
    FOREIGN KEY (replacement_payment_id, tenant_id) REFERENCES invoice_ar_payments,
    FOREIGN KEY (tenant_id, cash_point_id, sequence_number) REFERENCES kassasystem_journal_events(tenant_id, cash_point_id, sequence_number),
    UNIQUE (tenant_id, request_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses,
    FOREIGN KEY (zone_id, warehouse_id, tenant_id) REFERENCES warehouse_zones(id, warehouse_id, tenant_id)
);

CREATE INDEX ON sales_returns (tenant_id, cash_point_id, sequence_number);

CREATE INDEX ON sales_returns (tenant_id, order_id);

CREATE TABLE kassasystem_refunds (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    request_id uuid NOT NULL,
    source_journal_event_id bigint NOT NULL,
    sales_return_id int,
    kind varchar(20) NOT NULL CHECK (kind IN ('GOODS', 'ALLOWANCE')),
    reason varchar(500),
    created_by varchar(255) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    external_amount numeric(15,2) NOT NULL,
    lines jsonb NOT NULL CHECK (jsonb_typeof(lines) = 'array' AND jsonb_array_length(lines) > 0),
    external_lines jsonb NOT NULL,
    original_payment_attempt_id bigint,
    external_payment_attempt_id bigint,
    local_journal_event_id bigint,
    created_at timestamptz DEFAULT now() NOT NULL,
    CHECK (external_amount >= 0 AND external_amount <= amount),
    FOREIGN KEY (external_payment_attempt_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (local_journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id),
    FOREIGN KEY (original_payment_attempt_id, tenant_id) REFERENCES kassasystem_terminal_payments(id, tenant_id),
    FOREIGN KEY (sales_return_id, tenant_id) REFERENCES sales_returns(id, tenant_id),
    FOREIGN KEY (source_journal_event_id, tenant_id) REFERENCES kassasystem_journal_events(id, tenant_id),
    UNIQUE (tenant_id, request_id),
    UNIQUE (tenant_id, sales_return_id)
);

COMMENT ON TABLE kassasystem_refunds IS 'Durable monetary refund commands independent of physical stock receipt. Immutable original price/tax snapshots; external provider outcome remains authoritative.';

CREATE INDEX ON kassasystem_refunds (tenant_id, source_journal_event_id);

CREATE TABLE kassasystem_refund_allocations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    refund_id int NOT NULL,
    source_payment_id bigint NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    gift_card_id int,
    UNIQUE (tenant_id, refund_id, source_payment_id),
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id),
    FOREIGN KEY (refund_id, tenant_id) REFERENCES kassasystem_refunds(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (source_payment_id, tenant_id) REFERENCES kassasystem_journal_payments(id, tenant_id)
);

COMMENT ON TABLE kassasystem_refund_allocations IS 'Refund value allocated to original receipt tenders. Gift-card/cash portions settle atomically; only the external portion is dispatched to the provider.';

CREATE TABLE site_commerce_checkout_sessions (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    site_id int NOT NULL,
    public_token varchar(64) NOT NULL UNIQUE,
    idempotency_key varchar(255) NOT NULL,
    request_hash bytea NOT NULL,
    return_url text,
    currency char(3) NOT NULL,
    lines jsonb NOT NULL,
    net_amount numeric(15,2) NOT NULL,
    vat_amount numeric(15,2) NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    email varchar(255) CHECK (email IS NULL OR email = lower(email)),
    customer_snapshot jsonb,
    matched_customer_id int,
    fulfilled_customer_id int,
    fulfilled_order_id int,
    fulfilled_invoice_id int,
    updated_at timestamptz DEFAULT now() NOT NULL,
    shipping_method_public_id uuid,
    shipping_name varchar(200),
    shipping_vat_code varchar(10),
    shipping_net numeric(15,2),
    shipping_vat numeric(15,2),
    shipping_gross numeric(15,2),
    shipping_required bool DEFAULT false NOT NULL,
    discount_id int,
    discount_public_id uuid,
    discount_code varchar(40),
    discount_percentage smallint,
    discount_net numeric(15,2),
    discount_vat numeric(15,2),
    discount_gross numeric(15,2),
    market_id int NOT NULL,
    market_public_id uuid NOT NULL,
    market_handle varchar(100) NOT NULL CHECK (market_handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    locale varchar(35) NOT NULL CHECK (locale = btrim(locale) AND locale <> ''),
    shipping_token_key uuid DEFAULT gen_random_uuid() NOT NULL,
    selected_shipping_option jsonb CHECK (selected_shipping_option IS NULL OR jsonb_typeof(selected_shipping_option) = 'object'),
    discount_includes_free_shipping bool DEFAULT false NOT NULL,
    pickup_details varchar(1000),
    initial_country_code char(2) CHECK (initial_country_code ~ '^[A-Z]{2}$'),
    subscription_interval_months int CHECK (subscription_interval_months IS NULL OR subscription_interval_months >= 1 AND subscription_interval_months <= 12),
    subscription_id int,
    merchant_checkout_url text,
    revision int DEFAULT 0 NOT NULL CHECK (revision >= 0),
    discount_amount numeric(15,2),
    gift_card_id int,
    gift_card_code varchar(32),
    gift_card_amount numeric(15,2) DEFAULT 0 NOT NULL CHECK (gift_card_amount >= 0),
    gift_card_payment_id int,
    analytics_source text DEFAULT 'unknown' NOT NULL CHECK (analytics_source IN ('live', 'preview', 'unknown')),
    analytics_session_id uuid,
    analytics_test bool DEFAULT false NOT NULL,
    ordered_at timestamptz,
    payment_page_viewed_at timestamptz,
    comment varchar(2000),
    FOREIGN KEY (fulfilled_customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (fulfilled_invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (fulfilled_order_id, tenant_id) REFERENCES orders,
    FOREIGN KEY (gift_card_payment_id, tenant_id) REFERENCES invoice_ar_payments,
    FOREIGN KEY (matched_customer_id, tenant_id) REFERENCES customers,
    UNIQUE (site_id, tenant_id, idempotency_key),
    FOREIGN KEY (market_id, site_id, tenant_id) REFERENCES site_commerce_markets(id, site_id, tenant_id),
    CHECK (net_amount >= 0 AND vat_amount >= 0 AND total_amount >= 0),
    CONSTRAINT site_commerce_checkout_sessions_check1 CHECK (email IS NULL = (customer_snapshot IS NULL) OR merchant_checkout_url IS NOT NULL AND email IS NULL),
    CONSTRAINT site_commerce_checkout_sessions_check2 CHECK (discount_id IS NULL AND discount_public_id IS NULL AND discount_code IS NULL AND discount_percentage IS NULL AND discount_amount IS NULL AND discount_net IS NULL AND discount_vat IS NULL AND discount_gross IS NULL OR discount_public_id IS NOT NULL AND discount_code IS NOT NULL AND discount_net IS NOT NULL AND discount_vat IS NOT NULL AND discount_gross IS NOT NULL AND discount_code ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$' AND (discount_amount IS NULL OR discount_amount > 0 AND discount_percentage IS NULL) AND (discount_percentage IS NULL OR discount_percentage >= 0 AND discount_percentage <= 100) AND (COALESCE(discount_percentage, 0) > 0 OR discount_amount IS NOT NULL OR discount_includes_free_shipping) AND discount_net >= 0 AND discount_vat >= 0 AND discount_gross >= 0 AND discount_gross = discount_net + discount_vat),
    CONSTRAINT site_commerce_checkout_sessions_check3 CHECK (shipping_method_public_id IS NULL AND shipping_name IS NULL AND shipping_vat_code IS NULL AND shipping_net IS NULL AND shipping_vat IS NULL AND shipping_gross IS NULL OR shipping_method_public_id IS NOT NULL AND shipping_name IS NOT NULL AND length(btrim(shipping_name)) > 0 AND shipping_vat_code IS NOT NULL AND shipping_net IS NOT NULL AND shipping_vat IS NOT NULL AND shipping_gross IS NOT NULL AND shipping_net >= 0 AND shipping_vat >= 0 AND shipping_gross >= 0 AND shipping_gross = shipping_net + shipping_vat),
    CONSTRAINT site_commerce_checkout_sessions_check4 CHECK (pickup_details IS NULL OR shipping_method_public_id IS NOT NULL AND selected_shipping_option IS NULL AND shipping_gross = 0),
    FOREIGN KEY (discount_id, tenant_id) REFERENCES site_commerce_discounts ON DELETE SET NULL (discount_id),
    FOREIGN KEY (gift_card_id, tenant_id) REFERENCES gift_cards(id, tenant_id),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (site_id, tenant_id) REFERENCES sites(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions
);

CREATE UNIQUE INDEX ON site_commerce_checkout_sessions (id, fulfilled_order_id, tenant_id);

CREATE INDEX ON site_commerce_checkout_sessions (tenant_id, site_id, analytics_session_id) WHERE analytics_session_id IS NOT NULL;

CREATE INDEX ON site_commerce_checkout_sessions (tenant_id, site_id, payment_page_viewed_at) WHERE payment_page_viewed_at IS NOT NULL;

CREATE UNIQUE INDEX ON site_commerce_checkout_sessions (subscription_id, tenant_id) WHERE subscription_id IS NOT NULL;

CREATE INDEX ON site_commerce_checkout_sessions (tenant_id, gift_card_id) WHERE gift_card_id IS NOT NULL;

CREATE INDEX ON site_commerce_checkout_sessions (tenant_id, ordered_at) WHERE fulfilled_order_id IS NOT NULL;

CREATE TRIGGER checkout_discount_scope BEFORE INSERT OR UPDATE OF discount_id, tenant_id, market_id, site_id ON site_commerce_checkout_sessions FOR EACH ROW EXECUTE FUNCTION check_checkout_discount_scope();

CREATE TABLE inventory_reservations (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    variant_id int NOT NULL,
    warehouse_id int NOT NULL,
    quantity int NOT NULL CHECK (quantity > 0),
    status varchar(16) NOT NULL,
    site_commerce_checkout_session_id int,
    order_id int,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reference varchar(150),
    consumed_quantity int DEFAULT 0 NOT NULL,
    consumed_transaction_id int,
    source_location_id int,
    CHECK (site_commerce_checkout_session_id IS NULL <> (reference IS NULL)),
    CONSTRAINT inventory_reservations_check1 CHECK (consumed_quantity >= 0 AND consumed_quantity <= quantity),
    FOREIGN KEY (consumed_transaction_id, tenant_id) REFERENCES inventory_transactions,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE CASCADE,
    FOREIGN KEY (site_commerce_checkout_session_id, tenant_id) REFERENCES site_commerce_checkout_sessions(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (source_location_id, warehouse_id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id) ON DELETE SET NULL (source_location_id),
    UNIQUE (tenant_id, site_commerce_checkout_session_id, variant_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants ON DELETE RESTRICT,
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses ON DELETE RESTRICT
);

CREATE INDEX ON inventory_reservations (tenant_id, order_id) WHERE order_id IS NOT NULL;

CREATE UNIQUE INDEX ON inventory_reservations (tenant_id, reference, variant_id) WHERE reference IS NOT NULL;

CREATE INDEX ON inventory_reservations (tenant_id, variant_id, status);

CREATE TABLE payment_gateway_attempts (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int,
    customer_id int,
    business_grouping_reference varchar(64) NOT NULL,
    attempt_reference varchar(80) NOT NULL UNIQUE,
    attempt_number smallint NOT NULL CHECK (attempt_number > 0),
    idempotency_key varchar(64) NOT NULL UNIQUE,
    expected_processing_amount_value bigint NOT NULL,
    captured_processing_amount_value bigint,
    processing_currency varchar(3) NOT NULL CHECK (processing_currency ~ '^[A-Z]{3}$'),
    payment_link_id varchar(100),
    payment_psp_reference varchar(100),
    capture_psp_reference varchar(100),
    commercial_status varchar(30) NOT NULL,
    commercial_status_at timestamptz DEFAULT now() NOT NULL,
    commercial_failure_reason text,
    commercial_failed_at timestamptz,
    authorised_at timestamptz,
    captured_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    collection_url text,
    product_checkout_id int,
    checkout_session_id varchar(100),
    site_commerce_checkout_session_id int,
    checkout_session_data text,
    checkout_session_status varchar(20),
    checkout_session_finalization_key uuid,
    checkout_session_finalizing_at timestamptz,
    checkout_session_amount_value bigint,
    CHECK (checkout_session_amount_value IS NULL OR checkout_session_id IS NOT NULL AND checkout_session_amount_value > 0),
    CONSTRAINT payment_gateway_attempts_check1 CHECK (checkout_session_status IS NULL AND checkout_session_finalization_key IS NULL AND checkout_session_finalizing_at IS NULL OR checkout_session_id IS NOT NULL AND checkout_session_status IN ('UNPAYABLE', 'FINALIZING', 'PAYABLE') AND (checkout_session_status = 'FINALIZING' AND checkout_session_finalization_key IS NOT NULL AND checkout_session_finalizing_at IS NOT NULL OR checkout_session_status <> 'FINALIZING' AND checkout_session_finalization_key IS NULL AND checkout_session_finalizing_at IS NULL)),
    CONSTRAINT payment_gateway_attempts_check2 CHECK (product_checkout_id IS NULL OR site_commerce_checkout_session_id IS NULL),
    CONSTRAINT payment_gateway_attempts_check3 CHECK (collection_url IS NULL OR payment_link_id IS NOT NULL),
    CONSTRAINT payment_gateway_attempts_check4 CHECK (expected_processing_amount_value > 0 AND (captured_processing_amount_value IS NULL OR captured_processing_amount_value >= 0)),
    CONSTRAINT payment_gateway_attempts_check5 CHECK (invoice_id IS NOT NULL OR product_checkout_id IS NOT NULL OR site_commerce_checkout_session_id IS NOT NULL),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (product_checkout_id, tenant_id) REFERENCES product_checkouts(id, tenant_id),
    FOREIGN KEY (site_commerce_checkout_session_id, tenant_id) REFERENCES site_commerce_checkout_sessions(id, tenant_id)
);

CREATE UNIQUE INDEX ON payment_gateway_attempts (capture_psp_reference) WHERE capture_psp_reference IS NOT NULL;

CREATE UNIQUE INDEX ON payment_gateway_attempts (checkout_session_id) WHERE checkout_session_id IS NOT NULL;

CREATE INDEX ON payment_gateway_attempts (customer_id, tenant_id);

CREATE UNIQUE INDEX ON payment_gateway_attempts (payment_link_id) WHERE payment_link_id IS NOT NULL;

CREATE UNIQUE INDEX ON payment_gateway_attempts (payment_psp_reference) WHERE payment_psp_reference IS NOT NULL;

CREATE UNIQUE INDEX ON payment_gateway_attempts (tenant_id, invoice_id, attempt_number) WHERE invoice_id IS NOT NULL;

CREATE UNIQUE INDEX ON payment_gateway_attempts (tenant_id, product_checkout_id, attempt_number) WHERE product_checkout_id IS NOT NULL;

CREATE UNIQUE INDEX ON payment_gateway_attempts (tenant_id, site_commerce_checkout_session_id, attempt_number) WHERE site_commerce_checkout_session_id IS NOT NULL;

CREATE TABLE shipments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    order_id int NOT NULL,
    status varchar(32) NOT NULL,
    external_request_id varchar(64) NOT NULL CHECK (external_request_id = btrim(external_request_id) AND length(external_request_id) > 0),
    recipient_name varchar(150) NOT NULL,
    recipient_email varchar(320),
    recipient_phone varchar(40),
    recipient_country_code varchar(2) NOT NULL,
    recipient_city varchar(40) NOT NULL,
    recipient_postal_code varchar(20) NOT NULL,
    recipient_address_1 varchar(255) NOT NULL,
    recipient_address_2 varchar(75),
    delivery_method varchar(32) NOT NULL,
    parcel_weight_grams int NOT NULL CHECK (parcel_weight_grams > 0),
    parcel_length_cm numeric(8,2) NOT NULL,
    parcel_width_cm numeric(8,2) NOT NULL,
    parcel_height_cm numeric(8,2) NOT NULL,
    service_code varchar(100) NOT NULL CHECK (service_code = btrim(service_code) AND length(service_code) > 0),
    carrier_code varchar(32) NOT NULL CHECK (carrier_code = btrim(carrier_code) AND carrier_code ~ '^[A-Z][A-Z0-9_-]{0,31}$'),
    provider_shipment_id varchar(100),
    provider_item_id varchar(100),
    normalized_error_code varchar(64),
    normalized_error_message text,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    customer_shipping_charge_state varchar(16),
    customer_shipping_net numeric(12,2),
    customer_shipping_vat numeric(12,2),
    customer_shipping_gross numeric(12,2),
    customer_shipping_currency varchar(3),
    customer_shipping_vat_code varchar(5),
    customer_shipping_description varchar(255),
    carrier_price_amount numeric(12,2),
    carrier_price_currency varchar(3),
    pickup_provider_id varchar(100),
    pickup_name varchar(150),
    pickup_address varchar(255),
    pickup_postal_code varchar(20),
    pickup_city varchar(100),
    pickup_country_code varchar(2),
    pickup_type varchar(32),
    label_content_type varchar(100),
    label_bytes bytea,
    label_retrieval_reference varchar(2048),
    tracking_status varchar(32) DEFAULT 'NOT_AVAILABLE' NOT NULL,
    tracking_description varchar(1000),
    tracking_location varchar(255),
    tracking_estimated_delivery varchar(100),
    tracking_carrier_updated_at timestamptz,
    tracking_checked_at timestamptz,
    site_commerce_checkout_session_id int,
    transit_information jsonb,
    customer_shipping_order_line_id int,
    customer_shipping_order_line_pending bool DEFAULT false NOT NULL,
    provider_account_reference varchar(96),
    return_destination jsonb,
    inventory_recording_pending bool DEFAULT false NOT NULL,
    customs_invoice_bytes bytea,
    pickup_id int,
    label_print_code varchar(100),
    return_label_format varchar(10) DEFAULT 'PDF' NOT NULL CHECK (return_label_format IN ('PDF', 'QR')),
    notification_pending bool DEFAULT false NOT NULL,
    notification_retry_after timestamptz,
    notification_tracking_number varchar(100),
    inventory_pick_sources jsonb,
    CHECK (carrier_price_amount IS NULL AND carrier_price_currency IS NULL OR carrier_price_amount IS NOT NULL AND carrier_price_currency IS NOT NULL AND carrier_price_amount >= 0 AND carrier_price_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT shipments_check1 CHECK (site_commerce_checkout_session_id IS NULL AND customer_shipping_charge_state IS DISTINCT FROM 'PREPAID' OR site_commerce_checkout_session_id IS NOT NULL AND customer_shipping_charge_state IS NOT NULL AND customer_shipping_charge_state IN ('PREPAID', 'FREE')),
    CONSTRAINT shipments_check2 CHECK (customer_shipping_charge_state IS NULL AND customer_shipping_net IS NULL AND customer_shipping_vat IS NULL AND customer_shipping_gross IS NULL AND customer_shipping_currency IS NULL AND customer_shipping_vat_code IS NULL AND customer_shipping_description IS NULL OR customer_shipping_charge_state IS NOT NULL AND customer_shipping_charge_state IN ('BILLABLE', 'PREPAID', 'FREE', 'EXTERNAL') AND customer_shipping_net IS NOT NULL AND customer_shipping_vat IS NOT NULL AND customer_shipping_gross IS NOT NULL AND customer_shipping_currency IS NOT NULL AND customer_shipping_vat_code IS NOT NULL AND customer_shipping_description IS NOT NULL AND customer_shipping_gross = customer_shipping_net + customer_shipping_vat AND customer_shipping_currency ~ '^[A-Z]{3}$' AND customer_shipping_vat_code = btrim(customer_shipping_vat_code) AND length(customer_shipping_vat_code) > 0 AND customer_shipping_description = btrim(customer_shipping_description) AND length(customer_shipping_description) > 0 AND customer_shipping_net >= 0 AND customer_shipping_vat >= 0 AND (customer_shipping_charge_state IN ('BILLABLE', 'PREPAID') AND customer_shipping_gross > 0 OR customer_shipping_charge_state = 'FREE' AND customer_shipping_gross = 0 OR customer_shipping_charge_state = 'EXTERNAL' AND customer_shipping_gross >= 0)),
    CONSTRAINT shipments_check3 CHECK (customer_shipping_charge_state IS DISTINCT FROM 'EXTERNAL' OR customer_shipping_order_line_id IS NULL AND customer_shipping_order_line_pending = false),
    CONSTRAINT shipments_check4 CHECK ((label_bytes IS NULL AND label_content_type IS NULL OR label_bytes IS NOT NULL AND label_content_type IS NOT NULL AND label_content_type = btrim(label_content_type) AND length(label_content_type) > 0) AND (label_retrieval_reference IS NULL OR status = 'BOOKED' AND label_bytes IS NULL AND label_content_type IS NULL AND label_retrieval_reference = btrim(label_retrieval_reference) AND length(label_retrieval_reference) > 0)),
    CONSTRAINT shipments_check5 CHECK (parcel_length_cm > 0 AND parcel_width_cm > 0 AND parcel_height_cm > 0),
    CONSTRAINT shipments_check6 CHECK (pickup_provider_id IS NULL AND pickup_name IS NULL AND pickup_address IS NULL AND pickup_postal_code IS NULL AND pickup_city IS NULL AND pickup_country_code IS NULL AND pickup_type IS NULL OR pickup_provider_id IS NOT NULL AND pickup_name IS NOT NULL AND pickup_address IS NOT NULL AND pickup_postal_code IS NOT NULL AND pickup_city IS NOT NULL AND pickup_country_code IS NOT NULL AND pickup_type IS NOT NULL AND pickup_provider_id = btrim(pickup_provider_id) AND length(pickup_provider_id) > 0 AND pickup_name = btrim(pickup_name) AND length(pickup_name) > 0 AND pickup_address = btrim(pickup_address) AND length(pickup_address) > 0 AND pickup_postal_code = btrim(pickup_postal_code) AND length(pickup_postal_code) > 0 AND pickup_city = btrim(pickup_city) AND length(pickup_city) > 0 AND pickup_country_code ~ '^[A-Z]{2}$' AND pickup_type IN ('PICKUP_POINT', 'PARCEL_LOCKER')),
    CONSTRAINT shipments_check7 CHECK ((provider_shipment_id IS NULL OR provider_shipment_id = btrim(provider_shipment_id) AND length(provider_shipment_id) > 0) AND (provider_item_id IS NULL OR provider_item_id = btrim(provider_item_id) AND length(provider_item_id) > 0)),
    CONSTRAINT shipments_check8 CHECK (recipient_name = btrim(recipient_name) AND length(recipient_name) > 0 AND recipient_country_code ~ '^[A-Z]{2}$' AND recipient_city = btrim(recipient_city) AND length(recipient_city) > 0 AND recipient_postal_code = btrim(recipient_postal_code) AND length(recipient_postal_code) > 0 AND recipient_address_1 = btrim(recipient_address_1) AND length(recipient_address_1) > 0),
    CONSTRAINT shipments_check9 CHECK (delivery_method <> 'RETURN' OR site_commerce_checkout_session_id IS NULL AND return_destination IS NOT NULL AND customer_shipping_charge_state IS NULL AND NOT customer_shipping_order_line_pending),
    FOREIGN KEY (customer_shipping_order_line_id, order_id, tenant_id) REFERENCES order_lines(id, order_id, tenant_id) ON DELETE RESTRICT,
    UNIQUE (id, order_id, tenant_id),
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE RESTRICT,
    FOREIGN KEY (pickup_id, tenant_id) REFERENCES shipment_pickups(id, tenant_id),
    FOREIGN KEY (site_commerce_checkout_session_id, order_id, tenant_id) REFERENCES site_commerce_checkout_sessions(id, fulfilled_order_id, tenant_id),
    UNIQUE (tenant_id, external_request_id),
    UNIQUE (tenant_id, site_commerce_checkout_session_id)
);

CREATE UNIQUE INDEX ON shipments (carrier_code, provider_shipment_id) WHERE provider_shipment_id IS NOT NULL;

CREATE INDEX ON shipments (id) WHERE inventory_recording_pending;

CREATE INDEX ON shipments (notification_retry_after NULLS FIRST, id) WHERE notification_pending AND status = 'BOOKED';

CREATE INDEX ON shipments (tenant_id, created_at DESC, id DESC);

CREATE INDEX ON shipments (tenant_id, customer_shipping_order_line_id) WHERE customer_shipping_order_line_id IS NOT NULL;

CREATE INDEX ON shipments (tenant_id, order_id, created_at DESC);

CREATE INDEX ON shipments (tenant_id, provider_shipment_id) WHERE provider_shipment_id IS NOT NULL;

CREATE INDEX ON shipments (tenant_id, tracking_checked_at, id) WHERE status = 'BOOKED' AND tracking_status <> ALL (ARRAY['DELIVERED', 'RETURNED'::varchar]);

CREATE TABLE invoice_ar_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    invoice_id int NOT NULL,
    order_id int NOT NULL,
    order_line_id int,
    shipment_id int,
    row_number smallint NOT NULL CHECK (row_number > 0),
    item_name varchar(255) NOT NULL,
    comment text,
    quantity numeric(10,2) NOT NULL,
    cost_price numeric(10,2) NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    discount numeric(10,2),
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    discount_amount numeric(14,2),
    vat_code varchar(5) NOT NULL,
    variant_id int,
    accrual_enabled bool NOT NULL,
    accrual_period date,
    accrual_period_count smallint NOT NULL,
    sales_account_number varchar(4),
    group_heading bool DEFAULT false NOT NULL,
    end_group_before bool DEFAULT false NOT NULL,
    source_invoice_line_id int,
    CHECK (num_nonnulls(order_line_id, shipment_id, source_invoice_line_id) <= 1),
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar ON DELETE CASCADE,
    FOREIGN KEY (order_line_id, order_id, tenant_id) REFERENCES order_lines(id, order_id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (shipment_id, order_id, tenant_id) REFERENCES shipments(id, order_id, tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, invoice_id, order_id) REFERENCES invoice_ar_source_orders(tenant_id, invoice_id, order_id) DEFERRABLE INITIALLY DEFERRED,
    UNIQUE (tenant_id, invoice_id, row_number),
    UNIQUE (tenant_id, order_line_id)
);

CREATE UNIQUE INDEX ON invoice_ar_lines (id, order_id, tenant_id);

CREATE INDEX ON invoice_ar_lines (tenant_id, order_id);

CREATE UNIQUE INDEX ON invoice_ar_lines (tenant_id, shipment_id) WHERE shipment_id IS NOT NULL;

ALTER TABLE invoice_ar_lines ADD FOREIGN KEY (source_invoice_line_id, order_id, tenant_id) REFERENCES invoice_ar_lines(id, order_id, tenant_id);

CREATE TABLE shipment_lines (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    shipment_id int NOT NULL,
    order_id int NOT NULL,
    order_line_id int,
    item_name varchar(255) NOT NULL CHECK (item_name = btrim(item_name) AND length(item_name) > 0),
    quantity numeric(10,2) NOT NULL CHECK (quantity > 0),
    variant_id int,
    FOREIGN KEY (order_line_id, order_id, tenant_id) REFERENCES order_lines(id, order_id, tenant_id) ON DELETE SET NULL (order_line_id),
    FOREIGN KEY (shipment_id, order_id, tenant_id) REFERENCES shipments(id, order_id, tenant_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, shipment_id, order_line_id),
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants
);

CREATE INDEX ON shipment_lines (tenant_id, order_line_id);

CREATE TABLE shipment_return_emails (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    shipment_id int NOT NULL,
    order_id int NOT NULL,
    request_id uuid NOT NULL,
    recipient varchar(320) NOT NULL,
    outbox_id int NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (outbox_id, tenant_id) REFERENCES email_outbox(id, sender_tenant_id),
    FOREIGN KEY (shipment_id, order_id, tenant_id) REFERENCES shipments(id, order_id, tenant_id),
    UNIQUE (tenant_id, request_id)
);

CREATE INDEX ON shipment_return_emails (tenant_id, shipment_id, id DESC);

CREATE TABLE subscription_billings (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    subscription_id int NOT NULL,
    billing_date date NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    output_mode varchar(20) NOT NULL,
    generated_order_id int NOT NULL,
    generated_invoice_id int,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payment_reference varchar(255),
    payment_provider_reference varchar(32),
    payment_status varchar(20) DEFAULT 'NOT_ATTEMPTED' NOT NULL,
    payment_paid_at timestamptz,
    payment_attempt_count smallint DEFAULT 0 NOT NULL,
    payment_next_attempt_at timestamptz,
    payment_attempt_started_at timestamptz,
    CHECK (period_start <= period_end),
    FOREIGN KEY (generated_invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (generated_order_id, tenant_id) REFERENCES orders,
    UNIQUE (subscription_id, period_start, period_end),
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions ON DELETE CASCADE
);

CREATE INDEX ON subscription_billings (generated_invoice_id);

CREATE INDEX ON subscription_billings (generated_order_id);

CREATE UNIQUE INDEX ON subscription_billings (payment_reference) WHERE payment_reference IS NOT NULL;

CREATE INDEX ON subscription_billings (subscription_id, period_start DESC, id DESC);

CREATE TABLE timesheets (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    employee_id int NOT NULL,
    project_id int,
    date date NOT NULL,
    hours numeric(10,2) DEFAULT 0.00 NOT NULL,
    comment text,
    row_number smallint DEFAULT 0 NOT NULL,
    activity_id int REFERENCES activities,
    billing_order_id int,
    timer_minutes int CHECK (timer_minutes >= 0),
    FOREIGN KEY (billing_order_id, tenant_id) REFERENCES orders ON DELETE SET NULL (billing_order_id),
    FOREIGN KEY (employee_id, tenant_id) REFERENCES employees ON DELETE RESTRICT,
    FOREIGN KEY (project_id, tenant_id) REFERENCES projects
);

CREATE INDEX ON timesheets (billing_order_id) WHERE billing_order_id IS NOT NULL;

CREATE INDEX ON timesheets (employee_id);

CREATE INDEX ON timesheets (tenant_id, project_id, date) WHERE project_id IS NOT NULL AND hours > 0 AND billing_order_id IS NULL;

CREATE TABLE woocommerce_checkout_settlements (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    store_id int NOT NULL,
    external_order_id int NOT NULL,
    transaction_id varchar(255) NOT NULL,
    order_id int NOT NULL,
    invoice_id int NOT NULL,
    voucher_id bigint NOT NULL,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders,
    UNIQUE (store_id, external_order_id),
    FOREIGN KEY (store_id, tenant_id) REFERENCES woocommerce_store_config,
    UNIQUE (store_id, transaction_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE TABLE woocommerce_kustom_payments (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    store_id int NOT NULL,
    external_order_id int NOT NULL,
    order_id int,
    evidence jsonb,
    booked_evidence jsonb,
    outcome varchar(100) NOT NULL,
    last_attempt_at timestamptz NOT NULL,
    merchant_id varchar(100),
    environment varchar(4) CHECK (environment IS NULL OR environment IN ('LIVE', 'TEST')),
    provider_order_id varchar(255),
    capture_id varchar(255),
    voucher_id bigint,
    clearing_id int,
    customer_id int,
    invoice_id int,
    allocation_voucher_id bigint,
    accounting_type varchar(20) CHECK (accounting_type IS NULL OR accounting_type IN ('advance', 'invoice')),
    FOREIGN KEY (allocation_voucher_id, tenant_id) REFERENCES vouchers,
    CHECK (voucher_id IS NULL OR booked_evidence IS NOT NULL AND capture_id IS NOT NULL AND clearing_id IS NOT NULL AND customer_id IS NOT NULL AND accounting_type IS NOT NULL),
    FOREIGN KEY (clearing_id, tenant_id) REFERENCES sub_accounts,
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices_ar,
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders ON DELETE SET NULL (order_id),
    UNIQUE (store_id, external_order_id),
    FOREIGN KEY (store_id, tenant_id) REFERENCES woocommerce_store_config,
    CONSTRAINT woocommerce_kustom_payments_tenant_id_merchant_id_environm_key1 UNIQUE (tenant_id, merchant_id, environment, provider_order_id),
    UNIQUE (tenant_id, merchant_id, environment, capture_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE UNIQUE INDEX ON woocommerce_kustom_payments (id, tenant_id);

CREATE INDEX ON woocommerce_kustom_payments (store_id, last_attempt_at);

CREATE TABLE woocommerce_kustom_refunds (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    payment_id int NOT NULL,
    merchant_id varchar(100) NOT NULL,
    environment varchar(4) NOT NULL,
    provider_refund_id varchar(100) NOT NULL,
    amount numeric(15,2) NOT NULL CHECK (amount > 0),
    refunded_at timestamptz NOT NULL,
    voucher_id bigint NOT NULL,
    FOREIGN KEY (payment_id, tenant_id) REFERENCES woocommerce_kustom_payments(id, tenant_id),
    UNIQUE (tenant_id, merchant_id, environment, provider_refund_id),
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers,
    UNIQUE (voucher_id, tenant_id)
);

CREATE INDEX ON woocommerce_kustom_refunds (payment_id, tenant_id);

CREATE TABLE share_investment_events (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    share_investment_id int NOT NULL,
    event_type varchar(25) NOT NULL,
    event_date date NOT NULL,
    quantity numeric(20,6),
    price_per_unit numeric(20,6),
    fee_amount numeric(15,2) DEFAULT 0 NOT NULL,
    amount numeric(15,2) NOT NULL,
    cost_amount numeric(15,2),
    gain_or_loss_amount numeric(15,2),
    remaining_quantity numeric(20,6),
    remaining_cost_amount numeric(15,2),
    description text,
    external_reference varchar(60),
    voucher_id bigint,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tax_assessment text,
    withholding_tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    withholding_tax_receivable_amount numeric(15,2) DEFAULT 0 NOT NULL,
    tax_basis_amount numeric(15,2),
    opening_position bool DEFAULT false NOT NULL,
    opening_import_id int,
    opening_lot_order int,
    opening_lot_reference varchar(100),
    acquisition_date date,
    custody_reference varchar(200),
    custody_arrival_date date,
    evidence_reference varchar(4000),
    CHECK (opening_import_id IS NULL OR opening_position AND event_type = 'PURCHASE' AND voucher_id IS NULL AND opening_lot_order IS NOT NULL AND opening_lot_order > 0 AND opening_lot_reference IS NOT NULL AND btrim(opening_lot_reference) <> '' AND evidence_reference IS NOT NULL AND btrim(evidence_reference) <> '' AND quantity IS NOT NULL AND quantity > 0 AND amount >= 0 AND (acquisition_date IS NULL OR acquisition_date <= event_date) AND (custody_arrival_date IS NULL OR custody_arrival_date <= event_date)),
    CONSTRAINT share_investment_events_check1 CHECK (withholding_tax_amount >= 0 AND withholding_tax_receivable_amount >= 0 AND withholding_tax_receivable_amount <= withholding_tax_amount AND (event_type = 'DIVIDEND' OR withholding_tax_amount = 0)),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (share_investment_id, tenant_id) REFERENCES share_investments(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (voucher_id, tenant_id) REFERENCES vouchers
);

CREATE UNIQUE INDEX ON share_investment_events (id, share_investment_id, tenant_id);

CREATE UNIQUE INDEX ON share_investment_events (opening_import_id, opening_lot_order);

CREATE UNIQUE INDEX ON share_investment_events (opening_import_id, opening_lot_reference);

CREATE UNIQUE INDEX ON share_investment_events (tenant_id, external_reference) WHERE external_reference IS NOT NULL;

CREATE INDEX ON share_investment_events (tenant_id, share_investment_id, event_date, id);

CREATE TABLE share_investment_acquisition_date_reviews (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    event_id int NOT NULL,
    acquisition_date date CHECK (acquisition_date >= '1900-01-01' AND acquisition_date <= '9999-12-31'),
    rationale varchar(4000) NOT NULL CHECK (btrim(rationale) <> ''),
    recorded_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    recorded_by text NOT NULL CHECK (btrim(recorded_by) <> ''),
    recorded_by_api bool NOT NULL,
    FOREIGN KEY (event_id, tenant_id) REFERENCES share_investment_events(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON share_investment_acquisition_date_reviews (tenant_id, event_id, id);

CREATE TABLE share_investment_opening_imports (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL REFERENCES tenants ON DELETE CASCADE,
    share_investment_id int NOT NULL,
    import_reference varchar(100) NOT NULL CHECK (btrim(import_reference) <> ''),
    cutover_date date NOT NULL,
    replaced_opening_event_id int,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (id, share_investment_id, tenant_id),
    FOREIGN KEY (replaced_opening_event_id, share_investment_id, tenant_id) REFERENCES share_investment_events(id, share_investment_id, tenant_id),
    FOREIGN KEY (share_investment_id, tenant_id) REFERENCES share_investments(id, tenant_id),
    UNIQUE (share_investment_id, tenant_id)
);

CREATE TABLE share_investment_tax_basis_reviews (
    id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id int NOT NULL,
    event_id int NOT NULL,
    tax_basis_amount numeric(15,2),
    recorded_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rationale text NOT NULL CHECK (length(TRIM(BOTH FROM rationale)) > 0),
    FOREIGN KEY (event_id, tenant_id) REFERENCES share_investment_events(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ON share_investment_tax_basis_reviews (tenant_id, event_id, id);

CREATE MATERIALIZED VIEW bedri_shareholder_rankings AS
WITH latest_year AS (
         SELECT max(fiscal_year) AS fiscal_year
           FROM shareholder_register_compact
        ), rankings AS (
         SELECT org_number,
            fiscal_year,
            count(*)::int AS shareholder_count,
            count(*) FILTER (WHERE shareholder_org_number IS NOT NULL)::int AS corporate_shareholder_count,
            count(*) FILTER (WHERE share_percentage_micro >= 1000000)::int AS shareholders_above_one_percent
           FROM shareholder_register_compact shareholder
             JOIN latest_year USING (fiscal_year)
          WHERE NULLIF(btrim(shareholder_name), '') IS NOT NULL OR shareholder_org_number IS NOT NULL
          GROUP BY org_number, fiscal_year
          ORDER BY (count(*)::int) DESC, org_number
         LIMIT 200
        )
 SELECT org_number::text AS org_number,
    fiscal_year,
    shareholder_count,
    corporate_shareholder_count,
    shareholders_above_one_percent,
    CURRENT_TIMESTAMP AS refreshed_at
   FROM rankings;

CREATE UNIQUE INDEX ON bedri_shareholder_rankings (org_number);

CREATE INDEX ON bedri_shareholder_rankings (shareholder_count DESC, org_number);

CREATE VIEW brreg_companies AS
SELECT company.org_number,
    company.name,
    legal_form.code AS legal_form_code,
    industry_1.code AS industry_code_1,
    industry_1.description AS industry_desc_1,
    company.website,
    company.email,
    company.phone,
    company.mobile,
    company.street_address,
    lpad(company.postal_code::text, 4, '0') AS postal_code,
    COALESCE(visiting_place.name, company.city) AS city,
    company.registered_at,
    company.employee_count,
    company.vat_registered,
    company.activity_description,
    company.share_capital,
    company.postal_street_address,
    lpad(company.postal_postal_code::text, 4, '0') AS postal_postal_code,
    COALESCE(mailing_place.name, company.postal_city) AS postal_city,
        CASE
            WHEN company.legal_form_description_missing THEN NULL
            ELSE legal_form.description
        END AS legal_form_description,
    company.founded_on,
    company.purpose_description,
    company.vat_registered_on,
    company.nav_employer_registered_on,
    company.foretak_register_registered,
    company.foretak_register_registered_on,
    company.volunteer_register_registered,
    company.volunteer_register_registered_on,
    industry_2.code AS industry_code_2,
    industry_2.description AS industry_desc_2,
    industry_3.code AS industry_code_3,
    industry_3.description AS industry_desc_3,
    company.dissolution_started_on,
    company.bankruptcy_started_on,
    company.forced_liquidation_started_on,
    company.is_in_group,
    company.removed_from_brreg,
    municipality.name AS business_municipality,
    lpad(company.business_municipality_code::text, 4, '0') AS business_municipality_code,
    country.name AS business_country,
    country.code AS business_country_code,
    company.last_submitted_annual_accounts_year,
    company.old_name
   FROM brreg_companies_compact company
     JOIN brreg_legal_forms legal_form ON legal_form.id = company.legal_form_id
     LEFT JOIN brreg_industries industry_1 ON industry_1.id = company.industry_id_1
     LEFT JOIN brreg_industries industry_2 ON industry_2.id = company.industry_id_2
     LEFT JOIN brreg_industries industry_3 ON industry_3.id = company.industry_id_3
     LEFT JOIN brreg_municipalities municipality ON municipality.code = company.business_municipality_code
     LEFT JOIN brreg_countries country ON country.id = company.business_country_id
     LEFT JOIN brreg_postal_places visiting_place ON visiting_place.code = company.postal_code
     LEFT JOIN brreg_postal_places mailing_place ON mailing_place.code = company.postal_postal_code;

CREATE VIEW brreg_company_details AS
SELECT org_number,
    upper(btrim(business_country_code)) AS country_iso_code,
    company_address_title_case(city) AS city,
    postal_code,
    company_address_title_case(street_address) AS address_1,
    company_address_title_case(name) AS name,
    legal_form_code AS company_type_code,
    founded_on AS founded_date,
    business_municipality_code AS municipality_code
   FROM brreg_companies;

CREATE VIEW brreg_company_roles_compact_view AS
SELECT role.org_number,
    role_group_code,
    role_group_name,
    role_code,
    role_name,
        CASE
            WHEN role.holder_name_override IS NOT NULL THEN role.holder_name_override
            WHEN role.person_id IS NOT NULL THEN concat_ws(' ', person.first_name, person.middle_name, person.last_name)
            WHEN role.holder_org_number IS NOT NULL THEN holder_company.name
            ELSE NULL
        END AS holder_name,
    role.holder_org_number,
        CASE
            WHEN role.person_id IS NOT NULL THEN 'PERSON'
            WHEN role.holder_org_number IS NOT NULL THEN 'ENTITY'
            ELSE NULL
        END AS holder_type,
    person.first_name AS person_first_name,
    person.last_name AS person_last_name,
    role.is_active,
    person.name_key AS person_search_key,
    person.birth_date AS person_birth_date,
    role.person_id,
    person.middle_name AS person_middle_name
   FROM brreg_company_roles_compact role
     JOIN brreg_role_kinds role_kind ON id = role.role_kind_id
     LEFT JOIN brreg_person person ON person.id = role.person_id
     LEFT JOIN brreg_companies_compact holder_company ON holder_company.org_number = role.holder_org_number;

CREATE VIEW company_addresses AS
SELECT c.id AS company_id,
    c.tenant_id,
        CASE
            WHEN a.id IS NOT NULL THEN a.country_iso_code
            ELSE r.country_iso_code
        END AS country_iso_code,
    a.province,
        CASE
            WHEN a.id IS NOT NULL THEN a.city
            ELSE r.city
        END AS city,
        CASE
            WHEN a.id IS NOT NULL THEN a.postal_code
            ELSE r.postal_code
        END AS postal_code,
        CASE
            WHEN a.id IS NOT NULL THEN a.address_1
            ELSE r.address_1
        END AS address_1,
    a.address_2,
    a.recipient_name,
    a.contact_person,
    a.mobile,
    a.email
   FROM companies c
     LEFT JOIN addresses a ON a.id = c.address_id AND a.tenant_id = c.tenant_id
     LEFT JOIN brreg_company_details r ON r.org_number = c.registry_org_number
  WHERE a.id IS NOT NULL OR length(r.country_iso_code) = 2 AND (length(r.city) >= 1 AND length(r.city) <= 40) AND (length(r.address_1) >= 1 AND length(r.address_1) <= 255);

CREATE VIEW company_details AS
SELECT c.created_at,
    c.updated_at,
    c.id,
    c.address_id,
    COALESCE(c.founded_date, r.founded_date) AS founded_date,
    c.created_from_brreg,
    c.organization_number,
    COALESCE(c.name, r.name) AS name,
    c.country_code,
    COALESCE(c.company_type_code, r.company_type_code) AS company_type_code,
    COALESCE(c.municipality_code, r.municipality_code) AS municipality_code,
    c.tenant_id,
    c.registry_org_number
   FROM companies c
     LEFT JOIN brreg_company_details r ON r.org_number = c.registry_org_number;

CREATE VIEW supplier_posting_group_memberships AS
SELECT pgp.posting_id,
    pgp.posting_group_id,
    pgp.tenant_id
   FROM posting_group_postings pgp
     JOIN posting_groups pg ON pg.id = pgp.posting_group_id AND pg.tenant_id = pgp.tenant_id
     JOIN postings p ON p.id = pgp.posting_id AND p.tenant_id = pgp.tenant_id
  WHERE p.supplier_id IS NOT NULL AND pg.origin <> 'SALARY_PAYMENT'
UNION ALL
 SELECT posting_id,
    posting_group_id,
    tenant_id
   FROM supplier_posting_group_postings;

CREATE VIEW voucher_audit AS
SELECT tenant_id,
    voucher_id,
    (array_agg(changed_by ORDER BY changed_at, change_log_id) FILTER (WHERE operation = 'CREATED'))[1] AS created_by,
    max(changed_at) FILTER (WHERE operation = 'UPDATED') AS last_edited_at,
    (array_agg(changed_by ORDER BY changed_at DESC, change_log_id DESC) FILTER (WHERE operation = 'UPDATED'))[1] AS last_edited_by
   FROM ( SELECT cl.tenant_id,
            cl.entity_id AS voucher_id,
            cl.id AS change_log_id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
          WHERE cl.entity_name::text IN ('MANUAL_VOUCHER'::varchar, 'OPENING_BALANCE'::varchar, 'CUSTOMER_INVOICE_PAYMENT'::varchar, 'CUSTOMER_INVOICE_ADJUSTMENT'::varchar, 'INCOME'::varchar)
        UNION ALL
         SELECT cl.tenant_id,
            e.voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             JOIN expenses e ON e.id = cl.entity_id AND e.tenant_id = cl.tenant_id
          WHERE cl.entity_name = 'EXPENSE' AND e.voucher_id IS NOT NULL
        UNION ALL
         SELECT cl.tenant_id,
            t.voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             JOIN driving_log_trips t ON t.id = cl.entity_id AND t.tenant_id = cl.tenant_id
          WHERE cl.entity_name = 'DRIVING_LOG_TRIP' AND t.voucher_id IS NOT NULL
        UNION ALL
         SELECT cl.tenant_id,
            i.voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             JOIN invoices_ar i ON i.id = cl.entity_id AND i.tenant_id = cl.tenant_id
          WHERE cl.entity_name = 'CUSTOMERS_INVOICE' AND i.voucher_id IS NOT NULL
        UNION ALL
         SELECT cl.tenant_id,
            sp.voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             JOIN salary_payments sp ON sp.id = cl.entity_id AND sp.tenant_id = cl.tenant_id
          WHERE cl.entity_name = 'SALARY_PAYMENT' AND sp.voucher_id IS NOT NULL
        UNION ALL
         SELECT cl.tenant_id,
            cd.voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             JOIN customs_declarations cd ON cd.id = cl.entity_id AND cd.tenant_id = cl.tenant_id
          WHERE cl.entity_name = 'CUSTOMS_DECLARATION' AND cd.voucher_id IS NOT NULL
        UNION ALL
         SELECT cl.tenant_id,
            voucher_id,
            cl.id,
            cl.operation,
            cl.changed_by,
            cl.changed_at
           FROM change_log cl
             CROSS JOIN LATERAL ( SELECT DISTINCT p.voucher_id
                   FROM postings p
                     JOIN vouchers v ON v.id = p.voucher_id AND v.tenant_id = p.tenant_id
                  WHERE p.supplier_invoice_id = cl.entity_id AND p.tenant_id = cl.tenant_id AND v.voucher_type = 'SUPPLIER_INVOICE') si_voucher
          WHERE cl.entity_name = 'SUPPLIERS_INVOICE') voucher_change
  WHERE operation::text IN ('CREATED'::varchar, 'UPDATED'::varchar)
  GROUP BY tenant_id, voucher_id;

ALTER TABLE adyen_webhook_queue ADD CHECK (webhook_registration IN ('PAYMENT_STANDARD', 'RECURRING_TOKEN', 'PLATFORM_CONFIGURATION', 'PLATFORM_REPORT', 'PLATFORM_TERMINAL_ASSIGNMENT', 'PAYMENT_METHOD', 'PLATFORM_TRANSFER', 'PLATFORM_TRANSACTION')) NOT VALID;

ALTER TABLE tenants ADD FOREIGN KEY (company_id, id) REFERENCES companies DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE vouchers ADD FOREIGN KEY (vat_submission_id, tenant_id) REFERENCES vat_submissions ON DELETE SET NULL (vat_submission_id);

ALTER TABLE warehouses ADD FOREIGN KEY (receiving_location_id, id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id);

ALTER TABLE warehouses ADD FOREIGN KEY (sales_location_id, id, tenant_id) REFERENCES warehouse_locations(id, warehouse_id, tenant_id);

ALTER TABLE orders ADD FOREIGN KEY (exchange_return_id, tenant_id) REFERENCES sales_returns(id, tenant_id);

ALTER TABLE share_investment_events ADD FOREIGN KEY (opening_import_id, share_investment_id, tenant_id) REFERENCES share_investment_opening_imports(id, share_investment_id, tenant_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reai_readonly') THEN
        GRANT USAGE ON SCHEMA public TO reai_readonly;
        GRANT SELECT ON ALL TABLES IN SCHEMA public TO reai_readonly;
        GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO reai_readonly;
    END IF;
END
$$;

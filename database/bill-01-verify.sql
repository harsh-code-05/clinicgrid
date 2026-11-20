-- Read-only verification for BILL-01. The manifest runner enforces the target.
BEGIN READ ONLY;
SET LOCAL search_path TO pg_catalog;

DO $verify$
DECLARE
    audit_check text;
    expected record;
BEGIN
    FOR expected IN SELECT * FROM (VALUES
        ('bills', 'id', 'bigint', true),
        ('bills', 'invoice_number', 'character varying(32)', true),
        ('bills', 'patient_id', 'bigint', true),
        ('bills', 'appointment_id', 'bigint', true),
        ('bills', 'doctor_id', 'bigint', true),
        ('bills', 'amount', 'numeric(12,2)', true),
        ('bills', 'currency', 'character varying(3)', true),
        ('bills', 'status', 'character varying(16)', true),
        ('bills', 'payment_method', 'character varying(32)', false),
        ('bills', 'payment_reference', 'character varying(128)', false),
        ('bills', 'issued_at', 'timestamp(6) without time zone', true),
        ('bills', 'paid_at', 'timestamp(6) without time zone', false),
        ('bills', 'paid_by_admin_id', 'bigint', false),
        ('bills', 'voided_at', 'timestamp(6) without time zone', false),
        ('bills', 'voided_by_admin_id', 'bigint', false),
        ('bills', 'void_reason', 'character varying(500)', false),
        ('bills', 'created_at', 'timestamp(6) without time zone', true),
        ('bills', 'updated_at', 'timestamp(6) without time zone', true),
        ('bill_items', 'id', 'bigint', true),
        ('bill_items', 'bill_id', 'bigint', true),
        ('bill_items', 'description', 'character varying(500)', true),
        ('bill_items', 'quantity', 'integer', true),
        ('bill_items', 'unit_price', 'numeric(12,2)', true),
        ('bill_items', 'total_price', 'numeric(12,2)', true),
        ('payment_records', 'id', 'bigint', true),
        ('payment_records', 'bill_id', 'bigint', true),
        ('payment_records', 'amount', 'numeric(12,2)', true),
        ('payment_records', 'currency', 'character varying(3)', true),
        ('payment_records', 'payment_method', 'character varying(32)', true),
        ('payment_records', 'transaction_reference', 'character varying(128)', true),
        ('payment_records', 'recorded_by_admin_id', 'bigint', true),
        ('payment_records', 'recorded_at', 'timestamp(6) without time zone', true),
        ('payment_records', 'notes', 'character varying(500)', false)
    ) AS columns(table_name, column_name, column_type, required)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = to_regclass(format('public.%I', expected.table_name))
              AND attname = expected.column_name AND NOT attisdropped
              AND format_type(atttypid, atttypmod) = expected.column_type AND attnotnull = expected.required
              AND (expected.column_name <> 'id' OR attidentity = 'd')
        ) THEN
            RAISE EXCEPTION 'BILL-01 unexpected column %.%', expected.table_name, expected.column_name;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('bills', 'p', 'PRIMARY KEY (id)'),
        ('bills', 'u', 'UNIQUE (invoice_number)'),
        ('bills', 'u', 'UNIQUE (appointment_id)'),
        ('bills', 'f', 'FOREIGN KEY (patient_id) REFERENCES public.patients(id)'),
        ('bills', 'f', 'FOREIGN KEY (appointment_id) REFERENCES public.appointments(id)'),
        ('bills', 'f', 'FOREIGN KEY (doctor_id) REFERENCES public.doctors(id)'),
        ('bills', 'f', 'FOREIGN KEY (paid_by_admin_id) REFERENCES public.users(id)'),
        ('bills', 'f', 'FOREIGN KEY (voided_by_admin_id) REFERENCES public.users(id)'),
        ('bill_items', 'p', 'PRIMARY KEY (id)'),
        ('bill_items', 'f', 'FOREIGN KEY (bill_id) REFERENCES public.bills(id) ON DELETE CASCADE'),
        ('payment_records', 'p', 'PRIMARY KEY (id)'),
        ('payment_records', 'f', 'FOREIGN KEY (bill_id) REFERENCES public.bills(id) ON DELETE CASCADE'),
        ('payment_records', 'f', 'FOREIGN KEY (recorded_by_admin_id) REFERENCES public.users(id)')
    ) AS constraints(table_name, kind, definition)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = to_regclass(format('public.%I', expected.table_name))
              AND contype::text = expected.kind AND convalidated AND conenforced
              AND pg_get_constraintdef(oid, true) = expected.definition
        ) THEN
            RAISE EXCEPTION 'BILL-01 unexpected constraint on %: %', expected.table_name, expected.definition;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('bills', 'ck_bills_amount', 'amount>=0'),
        ('bills', 'ck_bills_currency', $e$currency='INR'$e$),
        ('bills', 'ck_bills_status', $e$status=ANYARRAY['UNPAID','PAID','VOID']$e$),
        ('bill_items', 'bill_items_description_check', $e$btrimdescription<>''$e$),
        ('bill_items', 'ck_bill_items_quantity', 'quantity>0'),
        ('bill_items', 'ck_bill_items_unit_price', 'unit_price>=0'),
        ('bill_items', 'ck_bill_items_total_price', 'total_price>=0'),
        ('payment_records', 'ck_payment_records_amount', 'amount>=0'),
        ('payment_records', 'ck_payment_records_currency', $e$currency='INR'$e$)
    ) AS checks(table_name, constraint_name, expression)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = to_regclass(format('public.%I', expected.table_name))
              AND conname = expected.constraint_name AND contype = 'c' AND convalidated AND conenforced
              AND regexp_replace(
                  regexp_replace(pg_get_expr(conbin, conrelid, true),
                      '::(character varying(\[\])?|text(\[\])?|numeric)', '', 'g'),
                  '[[:space:]()]', '', 'g'
              ) = expected.expression
        ) THEN
            RAISE EXCEPTION 'BILL-01 unexpected CHECK %', expected.constraint_name;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('bills', 'ix_bills_patient_id', 'patient_id, id DESC'),
        ('bills', 'ix_bills_doctor_id', 'doctor_id, id DESC'),
        ('bills', 'ix_bills_status', 'status'),
        ('bills', 'ix_bills_issued_at', 'issued_at DESC'),
        ('bill_items', 'ix_bill_items_bill_id', 'bill_id'),
        ('payment_records', 'ix_payment_records_bill_id', 'bill_id, id DESC')
    ) AS indexes(table_name, index_name, keys)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_index
            WHERE indexrelid = to_regclass(format('public.%I', expected.index_name))
              AND indrelid = to_regclass(format('public.%I', expected.table_name))
              AND indisvalid AND indisready
              AND pg_get_indexdef(indexrelid) = format('CREATE INDEX %I ON public.%I USING btree (%s)',
                  expected.index_name, expected.table_name, expected.keys)
        ) THEN
            RAISE EXCEPTION 'BILL-01 unexpected index %', expected.index_name;
        END IF;
    END LOOP;

    SELECT regexp_replace(
        regexp_replace(pg_get_expr(conbin, conrelid, true), '::(character varying(\[\])?|text(\[\])?)', '', 'g'),
        '[[:space:]()]', '', 'g'
    ) INTO audit_check
    FROM pg_constraint
    WHERE conrelid = 'public.audit_logs'::regclass AND conname = 'audit_logs_event_type_check'
      AND contype = 'c' AND convalidated AND conenforced;

    IF audit_check IS DISTINCT FROM $events$event_type=ANYARRAY['LOGIN_SUCCESS','LOGIN_FAILURE','PATIENT_BOOKED_APPOINTMENT','PATIENT_CANCELLED_APPOINTMENT','DOCTOR_CANCELLED_APPOINTMENT','ADMIN_CANCELLED_APPOINTMENT','DOCTOR_COMPLETED_APPOINTMENT','ADMIN_COMPLETED_APPOINTMENT','DOCTOR_CREATED_AVAILABILITY','DOCTOR_UPDATED_AVAILABILITY','ADMIN_CREATED_DOCTOR','ADMIN_UPDATED_DOCTOR','BILL_GENERATED','BILL_PAID','BILL_VOIDED']$events$ THEN
        RAISE EXCEPTION 'BILL-01 validated audit CHECK differs from the 15-event manifest';
    END IF;
    RAISE NOTICE 'PASS: BILL-01 columns, keys, validated checks, indexes and exact audit allowlist';
END;
$verify$;

COMMIT;

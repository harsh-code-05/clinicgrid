BEGIN;
SET LOCAL lock_timeout = '5s';

-- Apply after DISCO-01. Existing clinics remain unclassified until their district is verified.
ALTER TABLE public.doctor_locations
    ADD COLUMN lgd_district_code integer,
    ADD CONSTRAINT ck_doctor_locations_lgd_district_code CHECK (lgd_district_code > 0);

COMMIT;

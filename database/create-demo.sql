-- Reference-only schema. Original listing proof of concept remains separate.
CREATE TABLE IF NOT EXISTS demo_account (
  id uuid PRIMARY KEY, name text NOT NULL, email text NOT NULL UNIQUE,
  password_hash text NOT NULL, role text NOT NULL CHECK (role IN ('TENANT','LANDLORD'))
);
CREATE TABLE IF NOT EXISTS demo_session (
  token_hash text PRIMARY KEY, account_id uuid NOT NULL REFERENCES demo_account,
  expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS demo_listing (
  id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES demo_account,
  type text NOT NULL CHECK (type IN ('BUILDING','APARTMENT')), name text NOT NULL,
  description text NOT NULL, english_description text NOT NULL,
  address text NOT NULL, city text NOT NULL, active boolean NOT NULL DEFAULT true
);
CREATE TABLE IF NOT EXISTS demo_unit (
  id uuid PRIMARY KEY, listing_id uuid NOT NULL REFERENCES demo_listing,
  name text NOT NULL, size integer NOT NULL CHECK (size > 0), furnished boolean NOT NULL,
  rent integer NOT NULL CHECK (rent > 0), utilities integer NOT NULL CHECK (utilities >= 0),
  deposit integer NOT NULL CHECK (deposit >= 0), active boolean NOT NULL DEFAULT true
);
CREATE TABLE IF NOT EXISTS demo_period (
  id uuid PRIMARY KEY, unit_id uuid NOT NULL REFERENCES demo_unit,
  start_date date NOT NULL, end_date date NOT NULL CHECK (end_date > start_date),
  active boolean NOT NULL DEFAULT true
);
CREATE TABLE IF NOT EXISTS demo_quote (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES demo_account,
  period_id uuid NOT NULL REFERENCES demo_period, contract text NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS demo_rental (
  id uuid PRIMARY KEY, quote_id uuid UNIQUE REFERENCES demo_quote,
  unit_id uuid NOT NULL REFERENCES demo_unit, period_id uuid NOT NULL REFERENCES demo_period,
  tenant_id uuid NOT NULL REFERENCES demo_account, landlord_id uuid NOT NULL REFERENCES demo_account,
  start_date date NOT NULL, end_date date NOT NULL CHECK (end_date > start_date),
  original_end date NOT NULL, listing_name text NOT NULL, unit_name text NOT NULL,
  rent integer NOT NULL, utilities integer NOT NULL, deposit integer NOT NULL,
  contract text NOT NULL, refunded_at date
);
CREATE INDEX IF NOT EXISTS demo_rental_unit_dates ON demo_rental(unit_id, start_date, end_date);
CREATE TABLE IF NOT EXISTS demo_invoice (
  id uuid PRIMARY KEY, rental_id uuid NOT NULL REFERENCES demo_rental,
  start_date date NOT NULL, end_date date NOT NULL, due date NOT NULL, rent integer NOT NULL,
  utilities integer NOT NULL, deposit integer NOT NULL, paid_on date,
  UNIQUE(rental_id, start_date)
);
CREATE TABLE IF NOT EXISTS demo_message (
  id uuid PRIMARY KEY, rental_id uuid NOT NULL REFERENCES demo_rental,
  sender_id uuid NOT NULL REFERENCES demo_account, text text NOT NULL,
  problem boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS demo_saved (
  tenant_id uuid NOT NULL REFERENCES demo_account, target_id uuid NOT NULL,
  listing_id uuid NOT NULL REFERENCES demo_listing, unit_id uuid REFERENCES demo_unit,
  PRIMARY KEY(tenant_id, target_id)
);
CREATE TABLE IF NOT EXISTS demo_availability (
  unit_id uuid PRIMARY KEY REFERENCES demo_unit,
  available boolean NOT NULL
);
CREATE TABLE IF NOT EXISTS demo_notification (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES demo_account,
  listing_id uuid NOT NULL REFERENCES demo_listing, unit_id uuid NOT NULL REFERENCES demo_unit,
  name text NOT NULL, read boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS demo_announcement (
  id uuid PRIMARY KEY, listing_id uuid NOT NULL REFERENCES demo_listing,
  title text NOT NULL, text text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);

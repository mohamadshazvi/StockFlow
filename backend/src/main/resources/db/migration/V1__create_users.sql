CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND length(email) > 0),
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'STAFF')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Platform-wide switches that only administrators change: maintenance mode, open registration
-- and the active global announcement. One row per setting.
CREATE TABLE platform_settings (
 name VARCHAR(40) PRIMARY KEY,
 value TEXT NOT NULL,
 updated_by UUID REFERENCES users(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO platform_settings(name,value) VALUES ('maintenance','false'),('registration_open','true');

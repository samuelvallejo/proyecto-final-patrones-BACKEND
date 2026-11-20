-- Platform administrators. Every account is a regular user; ADMIN is granted on top of that.
-- Administrator actions are written to the existing audit_logs table with an ADMIN_ action.
INSERT INTO roles(name) VALUES ('ADMIN') ON CONFLICT (name) DO NOTHING;
CREATE INDEX IF NOT EXISTS audit_admin_actions ON audit_logs(created_at DESC) WHERE action LIKE 'ADMIN\_%';

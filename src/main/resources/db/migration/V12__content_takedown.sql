-- Logical takedown ("baja lógica"): an administrator hides a stream or clip without deleting it;
-- public lists and media access skip it and it can be restored later.
ALTER TABLE streams ADD COLUMN removed_at TIMESTAMPTZ;
ALTER TABLE streams ADD COLUMN removed_by UUID REFERENCES users(id);
ALTER TABLE streams ADD COLUMN removed_reason VARCHAR(500);
ALTER TABLE clips ADD COLUMN removed_at TIMESTAMPTZ;
ALTER TABLE clips ADD COLUMN removed_by UUID REFERENCES users(id);
ALTER TABLE clips ADD COLUMN removed_reason VARCHAR(500);
CREATE INDEX streams_removed ON streams(removed_at) WHERE removed_at IS NOT NULL;
CREATE INDEX clips_removed ON clips(removed_at) WHERE removed_at IS NOT NULL;

-- Rules enforced by the AI gateway proxy; administrators change them from the panel.
INSERT INTO platform_settings(name,value) VALUES ('ai_enabled','true'),('ai_hourly_limit','600')
  ON CONFLICT (name) DO NOTHING;

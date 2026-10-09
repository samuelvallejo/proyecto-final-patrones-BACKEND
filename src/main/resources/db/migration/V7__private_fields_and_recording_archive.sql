ALTER TABLE users ADD COLUMN email_encrypted TEXT;
ALTER TABLE users ADD COLUMN email_lookup VARCHAR(64);
CREATE UNIQUE INDEX users_email_lookup_unique ON users(email_lookup) WHERE email_lookup IS NOT NULL;
ALTER TABLE channels ADD COLUMN location_encrypted TEXT;
ALTER TABLE channels ADD COLUMN location_shared BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE channels ADD COLUMN location_updated_at TIMESTAMPTZ;

CREATE TABLE stream_recording_parts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
  asset_id UUID NOT NULL REFERENCES media_assets(id),
  start_seconds INT NOT NULL CHECK(start_seconds >= 0),
  end_seconds INT NOT NULL CHECK(end_seconds > start_seconds AND end_seconds-start_seconds <= 65),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(stream_id,start_seconds)
);
ALTER TABLE stream_recording_parts ENABLE ROW LEVEL SECURITY;
CREATE INDEX recording_parts_asset ON stream_recording_parts(asset_id);
-- This schema is private. Only the JDBC backend role may access its rows.
REVOKE ALL ON stream_recording_parts FROM PUBLIC;

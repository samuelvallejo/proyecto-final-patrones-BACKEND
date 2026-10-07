-- Bounded clip storage for deployments without a persistent disk.
CREATE TABLE media_asset_contents (
 asset_id UUID PRIMARY KEY REFERENCES media_assets(id) ON DELETE CASCADE,
 content BYTEA NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 31457280)
);
ALTER TABLE media_asset_contents ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON media_asset_contents FROM PUBLIC;
CREATE POLICY media_storage_backend ON media_asset_contents
 FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);

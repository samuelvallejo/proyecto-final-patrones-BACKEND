-- Indexes for the per-user lists the home dashboard requests on every visit.
CREATE INDEX IF NOT EXISTS notifications_user_time ON notifications(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS follows_user ON channel_follows(user_id);
CREATE INDEX IF NOT EXISTS streams_channel_status ON streams(channel_id, status);
CREATE INDEX IF NOT EXISTS moderators_user ON channel_moderators(user_id);

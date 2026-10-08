CREATE TABLE stream_collaborations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  primary_stream_id UUID NOT NULL REFERENCES streams(id),
  invite_hash VARCHAR(64) NOT NULL UNIQUE,
  max_participants SMALLINT NOT NULL DEFAULT 4 CHECK (max_participants BETWEEN 2 AND 4),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX one_active_collaboration_per_stream
  ON stream_collaborations(primary_stream_id) WHERE status = 'ACTIVE';

CREATE TABLE collaboration_members (
  room_id UUID NOT NULL REFERENCES stream_collaborations(id) ON DELETE CASCADE,
  stream_id UUID NOT NULL REFERENCES streams(id),
  user_id UUID NOT NULL REFERENCES users(id),
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  left_at TIMESTAMPTZ,
  PRIMARY KEY (room_id, stream_id)
);

CREATE UNIQUE INDEX one_active_collaboration_per_member_stream
  ON collaboration_members(stream_id) WHERE left_at IS NULL;
CREATE INDEX active_collaboration_members ON collaboration_members(room_id, joined_at) WHERE left_at IS NULL;

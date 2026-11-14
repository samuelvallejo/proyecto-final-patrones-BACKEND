-- Upcoming broadcasts announced by channel owners ("Eventos próximos").
CREATE TABLE scheduled_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  category_id UUID REFERENCES categories(id),
  title VARCHAR(140) NOT NULL CHECK (length(btrim(title)) > 0),
  starts_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX scheduled_events_start ON scheduled_events(starts_at);
CREATE INDEX scheduled_events_channel ON scheduled_events(channel_id, starts_at);

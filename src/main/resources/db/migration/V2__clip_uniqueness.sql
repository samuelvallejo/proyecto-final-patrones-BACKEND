CREATE UNIQUE INDEX one_clip_per_highlight ON clips(highlight_id) WHERE highlight_id IS NOT NULL;
CREATE INDEX segments_stream ON recording_segments(stream_id,start_seconds);

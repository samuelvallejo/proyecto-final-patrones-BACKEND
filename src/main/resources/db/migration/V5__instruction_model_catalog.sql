INSERT INTO ai_models(provider_id,name,capabilities)
SELECT id,'qwen3:4b-instruct','["moderation","summary","clip_metadata"]'::jsonb
FROM ai_providers WHERE name='Ollama'
ON CONFLICT(provider_id,name) DO NOTHING;

INSERT INTO ai_providers(name,base_url)
VALUES ('Ollama','http://127.0.0.1:11434')
ON CONFLICT(name) DO NOTHING;

INSERT INTO ai_models(provider_id,name,capabilities)
SELECT id,'qwen3:4b','["moderation","summary","clip_metadata"]'::jsonb
FROM ai_providers WHERE name='Ollama'
ON CONFLICT(provider_id,name) DO NOTHING;

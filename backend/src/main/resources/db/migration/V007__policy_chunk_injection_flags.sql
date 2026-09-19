-- F5 polish: per-chunk injection flags

ALTER TABLE policy_document_chunks
    ADD COLUMN IF NOT EXISTS injection_flags JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Document-level flags are now [{chunkId, phrase}, ...]. Clear legacy phrase-only arrays.
UPDATE policy_documents
SET injection_flags = '[]'::jsonb
WHERE injection_flags IS NOT NULL
  AND jsonb_typeof(injection_flags) = 'array'
  AND jsonb_array_length(injection_flags) > 0
  AND jsonb_typeof(injection_flags -> 0) = 'string';

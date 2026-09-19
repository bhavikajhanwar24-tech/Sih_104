-- F5: Policy document ingestion (tenant-scoped + FORCE RLS)

CREATE TABLE policy_documents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    title               TEXT NOT NULL,
    doc_type            TEXT NOT NULL
        CHECK (doc_type IN ('POLICY', 'SOP', 'COMPLIANCE', 'FAQ', 'OTHER')),
    original_filename   TEXT NOT NULL,
    mime_type           TEXT NOT NULL,
    size_bytes          BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 15728640),
    sha256              TEXT NOT NULL,
    content             BYTEA NOT NULL,
    extracted_text      TEXT,
    text_sha256         TEXT,
    language_detected   TEXT,
    status              TEXT NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED', 'EXTRACTED', 'FAILED', 'ARCHIVED')),
    uploaded_by         UUID REFERENCES users (id),
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    extraction_error    TEXT,
    injection_flags     JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT uq_policy_documents_tenant_sha256 UNIQUE (tenant_id, sha256)
);

CREATE TABLE policy_document_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    document_id     UUID NOT NULL REFERENCES policy_documents (id) ON DELETE CASCADE,
    ordinal         INT NOT NULL,
    heading_path    TEXT,
    text            TEXT NOT NULL,
    char_start      INT NOT NULL,
    char_end        INT NOT NULL,
    page_no         INT,
    CONSTRAINT uq_policy_chunks_doc_ordinal UNIQUE (document_id, ordinal)
);

CREATE INDEX idx_policy_documents_tenant_status ON policy_documents (tenant_id, status);
CREATE INDEX idx_policy_documents_tenant_uploaded ON policy_documents (tenant_id, uploaded_at DESC);
CREATE INDEX idx_policy_chunks_document ON policy_document_chunks (tenant_id, document_id, ordinal);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['policy_documents', 'policy_document_chunks']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
               USING (tenant_id = app_current_tenant_id())
               WITH CHECK (tenant_id = app_current_tenant_id())',
            t
        );
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO sv_app', t);
    END LOOP;
END $$;

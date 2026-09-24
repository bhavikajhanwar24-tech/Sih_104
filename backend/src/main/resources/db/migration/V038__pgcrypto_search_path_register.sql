-- Ensure pgcrypto digest() is visible to SECURITY DEFINER register (Supabase uses schema extensions).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
BEGIN
    -- Prefer extensions schema when present (Supabase)
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'extensions') THEN
        EXECUTE $f$
            ALTER FUNCTION fn_register_tenant(uuid, text, text, text, text, uuid, text, text, text)
            SET search_path = public, extensions
        $f$;
    ELSE
        EXECUTE $f$
            ALTER FUNCTION fn_register_tenant(uuid, text, text, text, text, uuid, text, text, text)
            SET search_path = public
        $f$;
    END IF;
EXCEPTION
    WHEN undefined_function THEN NULL;
END $$;

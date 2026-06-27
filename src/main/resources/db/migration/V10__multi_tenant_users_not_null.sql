-- =====================================================================
-- Finalisation sécurité : tout utilisateur DOIT appartenir à un établissement.
-- Garantit qu'aucune session ne peut exister sans tenant -> le filtre d'isolation
-- est toujours actif (fail-closed). Backfill de sécurité avant la contrainte.
-- =====================================================================

UPDATE users
SET etablissement_id = (SELECT id FROM etablissements WHERE slug = 'default')
WHERE etablissement_id IS NULL;

ALTER TABLE users ALTER COLUMN etablissement_id SET NOT NULL;

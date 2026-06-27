-- =====================================================================
-- Intégrité multi-tenant : toute donnée MÉTIER doit appartenir à un établissement.
-- Garde-fou DB contre les lignes "orphelines" (etablissement_id NULL) : invisibles
-- de tous les tenants (donnée perdue) ou fuiteuses si une requête contourne le filtre.
-- Backfill de sécurité d'abord, puis NOT NULL. (users : déjà fait en V10.)
-- =====================================================================

UPDATE enseignants         SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE seances             SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE emargements         SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE classes             SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE matieres            SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE salles              SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE demandes_rattrapage SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE rapports_pdf        SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;

ALTER TABLE enseignants         ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE seances             ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE emargements         ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE classes             ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE matieres            ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE salles              ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE demandes_rattrapage ALTER COLUMN etablissement_id SET NOT NULL;
ALTER TABLE rapports_pdf        ALTER COLUMN etablissement_id SET NOT NULL;

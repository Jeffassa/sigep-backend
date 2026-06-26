-- =====================================================================
-- Module 2 — Isolation multi-tenant (entités pilotes : enseignants, séances)
-- Ajoute etablissement_id, rattache l'existant au tenant par défaut, FK + index.
-- (Les autres entités métier suivront le même schéma au Module 2b.)
-- =====================================================================

ALTER TABLE enseignants ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE seances     ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;

UPDATE enseignants
SET etablissement_id = (SELECT id FROM etablissements WHERE slug = 'default')
WHERE etablissement_id IS NULL;

UPDATE seances
SET etablissement_id = (SELECT id FROM etablissements WHERE slug = 'default')
WHERE etablissement_id IS NULL;

ALTER TABLE enseignants
    ADD CONSTRAINT fk_enseignants_etablissement
    FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);

ALTER TABLE seances
    ADD CONSTRAINT fk_seances_etablissement
    FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);

CREATE INDEX IF NOT EXISTS idx_enseignants_etablissement ON enseignants(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_seances_etablissement ON seances(etablissement_id);

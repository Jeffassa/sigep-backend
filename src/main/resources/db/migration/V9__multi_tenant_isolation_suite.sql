-- =====================================================================
-- Module 2b — Isolation multi-tenant : entités métier restantes.
-- Même schéma que V8 : etablissement_id + backfill au tenant par défaut + FK + index.
-- =====================================================================

ALTER TABLE emargements         ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE classes             ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE matieres            ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE salles              ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE demandes_rattrapage ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;
ALTER TABLE rapports_pdf        ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;

UPDATE emargements         SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE classes             SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE matieres            SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE salles              SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE demandes_rattrapage SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;
UPDATE rapports_pdf        SET etablissement_id = (SELECT id FROM etablissements WHERE slug='default') WHERE etablissement_id IS NULL;

ALTER TABLE emargements         ADD CONSTRAINT fk_emargements_etablissement         FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);
ALTER TABLE classes             ADD CONSTRAINT fk_classes_etablissement             FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);
ALTER TABLE matieres            ADD CONSTRAINT fk_matieres_etablissement            FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);
ALTER TABLE salles              ADD CONSTRAINT fk_salles_etablissement              FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);
ALTER TABLE demandes_rattrapage ADD CONSTRAINT fk_demandes_rattrapage_etablissement FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);
ALTER TABLE rapports_pdf        ADD CONSTRAINT fk_rapports_pdf_etablissement        FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);

CREATE INDEX IF NOT EXISTS idx_emargements_etablissement         ON emargements(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_classes_etablissement             ON classes(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_matieres_etablissement            ON matieres(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_salles_etablissement              ON salles(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_demandes_rattrapage_etablissement ON demandes_rattrapage(etablissement_id);
CREATE INDEX IF NOT EXISTS idx_rapports_pdf_etablissement        ON rapports_pdf(etablissement_id);

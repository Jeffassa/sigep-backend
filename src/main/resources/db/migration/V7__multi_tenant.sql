-- =====================================================================
-- Module 1 — Fondations multi-tenant (SaaS)
-- Crée la table des établissements (tenants) et rattache les utilisateurs
-- existants à un tenant par défaut. L'isolation des données métier
-- (enseignants, séances, émargements, ...) sera ajoutée au Module 2.
-- =====================================================================

CREATE TABLE IF NOT EXISTS etablissements (
    id              BIGSERIAL PRIMARY KEY,
    nom             VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    plan            VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    actif           BOOLEAN      NOT NULL DEFAULT TRUE,
    max_enseignants INT          NOT NULL DEFAULT 10,
    date_creation   TIMESTAMP    NOT NULL DEFAULT now()
);

-- Tenant par défaut : reçoit les données du mode mono-établissement actuel.
INSERT INTO etablissements (nom, slug, plan, max_enseignants)
SELECT 'Établissement par défaut', 'default', 'ENTERPRISE', 0
WHERE NOT EXISTS (SELECT 1 FROM etablissements WHERE slug = 'default');

-- Rattachement des utilisateurs existants (colonne nullable -> backfill -> FK/index).
ALTER TABLE users ADD COLUMN IF NOT EXISTS etablissement_id BIGINT;

UPDATE users
SET etablissement_id = (SELECT id FROM etablissements WHERE slug = 'default')
WHERE etablissement_id IS NULL;

ALTER TABLE users
    ADD CONSTRAINT fk_users_etablissement
    FOREIGN KEY (etablissement_id) REFERENCES etablissements(id);

CREATE INDEX IF NOT EXISTS idx_users_etablissement ON users(etablissement_id);

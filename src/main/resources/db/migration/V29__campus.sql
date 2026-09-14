-- Campus : la grille tarifaire vendait « 1 campus » au plan gratuit et « multi-campus » au plan
-- Pro, alors que la notion n'existait nulle part. Le mot n'apparaissait que dans le nom d'une
-- constante de la table des plans.
--
-- Un campus regroupe des salles. Un établissement qui tient plusieurs sites — une école avec
-- une annexe, une université avec deux villes — sépare ainsi ses salles, et lit ses présences
-- site par site.
CREATE TABLE IF NOT EXISTS campus (
    id                BIGSERIAL PRIMARY KEY,
    nom               VARCHAR(150) NOT NULL,
    adresse           VARCHAR(255),
    etablissement_id  BIGINT NOT NULL REFERENCES etablissements (id),
    CONSTRAINT uk_campus_etab_nom UNIQUE (etablissement_id, nom)
);

-- L'isolation entre établissements passe par cette colonne : elle est lue à chaque requête.
CREATE INDEX IF NOT EXISTS idx_campus_etablissement ON campus (etablissement_id);

-- Une salle appartient à un campus. La colonne reste NULLABLE : les salles déjà saisies n'en
-- ont aucun, et exiger un campus rétroactivement bloquerait tous les établissements existants.
ALTER TABLE salles ADD COLUMN IF NOT EXISTS campus_id BIGINT REFERENCES campus (id);
CREATE INDEX IF NOT EXISTS idx_salles_campus ON salles (campus_id);

COMMENT ON TABLE campus IS
    'Site physique d''un établissement. Regroupe des salles. Le plan gratuit en autorise un seul.';
COMMENT ON COLUMN salles.campus_id IS
    'Campus de rattachement. NULL pour les salles antérieures à la notion de campus.';

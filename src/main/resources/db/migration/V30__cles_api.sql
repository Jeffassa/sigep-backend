-- API publique : l'offre Enterprise vendait « SSO & API » alors qu'aucune des deux n'existait.
-- Feature.API_PUBLIQUE n'était consultée nulle part.
--
-- Une clé donne accès aux données d'un établissement. Elle est donc traitée comme un mot de
-- passe : seule son EMPREINTE est conservée. Une base qui fuite ne livre alors aucune clé
-- utilisable, et personne — pas même nous — ne peut relire une clé perdue : on en émet une
-- nouvelle.
CREATE TABLE IF NOT EXISTS cles_api (
    id                   BIGSERIAL PRIMARY KEY,
    libelle              VARCHAR(120) NOT NULL,
    -- SHA-256 en hexadécimal : 64 caractères.
    empreinte            VARCHAR(64)  NOT NULL UNIQUE,
    -- Début de la clé, conservé en clair pour que l'administrateur reconnaisse la sienne
    -- dans la liste. Trop court pour aider qui tenterait de la deviner.
    prefixe              VARCHAR(16)  NOT NULL,
    etablissement_id     BIGINT       NOT NULL REFERENCES etablissements (id),
    creee_le             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    derniere_utilisation TIMESTAMP,
    revoquee_le          TIMESTAMP
);

-- L'empreinte est lue à chaque appel d'API : c'est le chemin chaud.
CREATE INDEX IF NOT EXISTS idx_cles_api_empreinte ON cles_api (empreinte);
CREATE INDEX IF NOT EXISTS idx_cles_api_etablissement ON cles_api (etablissement_id);

COMMENT ON COLUMN cles_api.empreinte IS
    'SHA-256 de la clé. La clé en clair n''est affichée qu''une fois, à sa création.';
COMMENT ON COLUMN cles_api.revoquee_le IS
    'Révocation : la ligne est conservée pour garder la trace de ce qui a existé.';

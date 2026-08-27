-- E4 (sécurité) : anti-rejeu PERSISTANT des tokens QR d'émargement.
-- Remplace la map en mémoire (perdue au redémarrage, incohérente en multi-instance).
-- cle = "<enseignantId>:<jti>" ; expire_le borne la rétention (purge planifiée).
CREATE TABLE jti_consommes (
    cle       VARCHAR(128) PRIMARY KEY,
    expire_le TIMESTAMP NOT NULL
);

-- Purge efficace des lignes périmées.
CREATE INDEX idx_jti_consommes_expire_le ON jti_consommes (expire_le);

-- Journal des évènements de sécurité consultable par le super-administrateur.
-- Jusqu'ici ces détections n'existaient que dans les journaux du serveur : invisibles depuis
-- l'interface, et perdues à chaque redémarrage de l'hébergement.
CREATE TABLE IF NOT EXISTS evenements_securite (
    id                BIGSERIAL PRIMARY KEY,
    date_heure        TIMESTAMP    NOT NULL,
    type              VARCHAR(48)  NOT NULL,
    severite          VARCHAR(16)  NOT NULL,
    ip                VARCHAR(64),
    identifiant       VARCHAR(180),
    etablissement_id  BIGINT,
    chemin            VARCHAR(255),
    details           VARCHAR(500)
);

-- La console affiche par défaut les évènements récents, du plus récent au plus ancien.
CREATE INDEX IF NOT EXISTS idx_evenements_securite_date ON evenements_securite (date_heure DESC);
-- Filtrage par gravité (« montre-moi les critiques ») et regroupement par IP (« qui insiste ? »).
CREATE INDEX IF NOT EXISTS idx_evenements_securite_severite ON evenements_securite (severite, date_heure DESC);
CREATE INDEX IF NOT EXISTS idx_evenements_securite_ip ON evenements_securite (ip, date_heure DESC);

-- Annuaire des écoles et universités de l'UEMOA, collecté depuis OpenStreetMap (licence ODbL).
-- Il ne sert qu'à proposer un nom à l'inscription : aucune donnée de client n'y figure.
CREATE TABLE annuaire_etablissements (
    id            BIGSERIAL PRIMARY KEY,
    nom           VARCHAR(255) NOT NULL,
    nom_recherche VARCHAR(255) NOT NULL,
    type          VARCHAR(30)  NOT NULL,
    ville         VARCHAR(120),
    pays_code     VARCHAR(2)   NOT NULL,
    source        VARCHAR(20)  NOT NULL,
    source_id     VARCHAR(40)  NOT NULL,
    latitude      DOUBLE PRECISION,
    longitude     DOUBLE PRECISION,
    date_collecte TIMESTAMP    NOT NULL,
    CONSTRAINT uk_annuaire_source UNIQUE (source, source_id)
);
CREATE INDEX idx_annuaire_pays ON annuaire_etablissements (pays_code);
CREATE INDEX idx_annuaire_nom ON annuaire_etablissements (nom_recherche);

-- Dernière collecte de chaque pays.
CREATE TABLE annuaire_collectes (
    pays_code     VARCHAR(2) PRIMARY KEY,
    date_collecte TIMESTAMP,
    nombre        INTEGER,
    erreur        VARCHAR(300)
);

-- Pays d'un établissement client (code ISO à deux lettres), choisi à l'inscription.
ALTER TABLE etablissements ADD COLUMN pays_code VARCHAR(2);

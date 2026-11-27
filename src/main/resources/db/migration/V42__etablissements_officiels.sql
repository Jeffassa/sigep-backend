-- Listes officielles des établissements autorisés par l'État en Côte d'Ivoire, recopiées chez nous :
-- enseignement supérieur privé (MESRS), collèges et lycées (MENA, classement du BEPC), et
-- établissements publics du supérieur saisis par SIGEP. Données publiques ; aucun client n'y figure.
CREATE TABLE etablissements_officiels (
    id            BIGSERIAL PRIMARY KEY,
    source        VARCHAR(30)  NOT NULL,
    categorie     VARCHAR(30)  NOT NULL,
    code_officiel VARCHAR(20),
    nom           VARCHAR(255) NOT NULL,
    nom_recherche VARCHAR(255) NOT NULL,
    nom_cle       VARCHAR(255) NOT NULL,
    ville         VARCHAR(120),
    commune       VARCHAR(120),
    date_import   TIMESTAMP    NOT NULL
);
CREATE INDEX idx_officiels_source ON etablissements_officiels (source);
CREATE INDEX idx_officiels_recherche ON etablissements_officiels (nom_recherche);
CREATE INDEX idx_officiels_cle ON etablissements_officiels (nom_cle);

-- Dernier import de chaque liste.
CREATE TABLE officiels_imports (
    source      VARCHAR(30) PRIMARY KEY,
    date_import TIMESTAMP,
    nombre      INTEGER,
    erreur      VARCHAR(300)
);

-- Rapprochement d'un établissement client avec les listes officielles. La fiche officielle retenue
-- est recopiée (source, code, nom) : les listes sont remplacées à chaque import.
ALTER TABLE etablissements ADD COLUMN reconnaissance VARCHAR(20) NOT NULL DEFAULT 'A_VERIFIER';
ALTER TABLE etablissements ADD COLUMN officiel_source VARCHAR(30);
ALTER TABLE etablissements ADD COLUMN officiel_code VARCHAR(20);
ALTER TABLE etablissements ADD COLUMN officiel_nom VARCHAR(255);
ALTER TABLE etablissements ADD COLUMN reconnaissance_auteur VARCHAR(255);
ALTER TABLE etablissements ADD COLUMN reconnaissance_date TIMESTAMP;
ALTER TABLE etablissements ADD COLUMN reconnaissance_constat VARCHAR(500);

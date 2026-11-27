-- APK de l'application Android, déposé par la plateforme et servi depuis sigep.store.
-- Une seule version est gardée : un nouveau dépôt remplace le précédent.
-- Le fichier est découpé en morceaux d'un mégaoctet, pour être écrit et servi sans le charger
-- entier en mémoire.
CREATE TABLE application_mobile (
    id          BIGSERIAL PRIMARY KEY,
    version     VARCHAR(20)  NOT NULL,
    taille      BIGINT       NOT NULL,
    sha256      VARCHAR(64)  NOT NULL,
    morceaux    INTEGER      NOT NULL,
    date_depot  TIMESTAMP    NOT NULL,
    depose_par  VARCHAR(180)
);

CREATE TABLE application_mobile_morceaux (
    application_id  BIGINT  NOT NULL REFERENCES application_mobile (id) ON DELETE CASCADE,
    numero          INTEGER NOT NULL,
    donnees         BYTEA   NOT NULL,
    PRIMARY KEY (application_id, numero)
);

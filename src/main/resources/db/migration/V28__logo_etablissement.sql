-- Logo de l'établissement : la fonctionnalité était vendue dans le plan Pro, mais rien ne
-- permettait de déposer une image. La colonne logo_url existait sans jamais être renseignée.
--
-- L'image est stockée EN BASE, et non sur le disque : l'hébergement recrée le système de
-- fichiers à chaque déploiement, un fichier déposé y serait perdu au premier redémarrage —
-- et l'établissement verrait son logo disparaître sans comprendre pourquoi.
ALTER TABLE etablissements
    ADD COLUMN IF NOT EXISTS logo_donnees BYTEA,
    ADD COLUMN IF NOT EXISTS logo_type    VARCHAR(64);

COMMENT ON COLUMN etablissements.logo_donnees IS
    'Image du logo. En base plutôt que sur disque : le disque de l''hébergement est éphémère.';
COMMENT ON COLUMN etablissements.logo_type IS
    'Type MIME de l''image (image/png, image/jpeg, image/webp, image/svg+xml).';

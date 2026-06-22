-- Les rapports PDF étaient seulement écrits sur disque (cheminFichier). Or le disque
-- de l'hébergeur (Render free) est éphémère : le fichier disparaît au redéploiement ou
-- à la veille -> téléchargement en HTTP 404. On stocke désormais les octets en base.
ALTER TABLE rapports_pdf ADD COLUMN contenu_pdf bytea;

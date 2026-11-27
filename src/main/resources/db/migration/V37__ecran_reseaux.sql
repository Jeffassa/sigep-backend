-- Réseau d'où l'écran d'émargement peut s'afficher (IP ou plages CIDR). Vide : partout.
ALTER TABLE etablissements ADD COLUMN ecran_reseaux VARCHAR(500);

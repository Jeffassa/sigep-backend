-- Émargement hors-ligne : présence non vérifiée par QR (file d'attente envoyée au retour du réseau)
ALTER TABLE emargements ADD COLUMN hors_ligne BOOLEAN NOT NULL DEFAULT FALSE;

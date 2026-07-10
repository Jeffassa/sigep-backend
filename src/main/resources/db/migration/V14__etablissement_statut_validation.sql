-- SA-2 : validation des inscriptions par le super admin. Toute inscription self-service
-- naît EN_ATTENTE (connexion bloquée jusqu'à validation). Les établissements existants
-- sont considérés VALIDE (défaut de la colonne).
ALTER TABLE etablissements ADD COLUMN statut VARCHAR(20) NOT NULL DEFAULT 'VALIDE';
ALTER TABLE etablissements ADD CONSTRAINT etablissements_statut_check
    CHECK (statut IN ('EN_ATTENTE', 'VALIDE', 'REFUSE'));

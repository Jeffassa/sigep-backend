-- Statut ARCHIVE : enseignant parti de l'établissement.
-- La contrainte d'origine (V1) n'énumérait que trois valeurs ; sans cette migration, tout
-- archivage échouerait en base alors que le code Java l'autorise.
ALTER TABLE enseignants DROP CONSTRAINT IF EXISTS enseignants_statut_check;

ALTER TABLE enseignants ADD CONSTRAINT enseignants_statut_check
    CHECK (statut IN ('VALIDATED', 'PENDING', 'REJECTED', 'ARCHIVE'));

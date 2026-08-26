-- C2 (sécurité anti-fraude) : l'émargement hors-ligne devient une présence À CONFIRMER.
-- 1) Nouvelle colonne "valide" sur emargements :
--    - true  pour les émargements en ligne (et les hors-ligne historiques, réputés déjà pris en compte) ;
--    - false pour les nouveaux hors-ligne, tant que l'admin ne les a pas validés.
ALTER TABLE emargements ADD COLUMN valide boolean NOT NULL DEFAULT true;

-- 2) Nouveau statut de séance EN_ATTENTE_VALIDATION : on recrée la contrainte CHECK
--    pour l'autoriser (Postgres ne permet pas d'ajouter une valeur à un CHECK existant).
ALTER TABLE seances DROP CONSTRAINT IF EXISTS seances_statut_check;
ALTER TABLE seances ADD CONSTRAINT seances_statut_check
    CHECK ((statut)::text = ANY ((ARRAY[
        'A_FAIRE'::character varying,
        'EMARGE'::character varying,
        'EN_RETARD'::character varying,
        'EN_ATTENTE_VALIDATION'::character varying
    ])::text[]));

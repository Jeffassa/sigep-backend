-- Nouveau statut de séance A_VALIDER : séance importée par un enseignant, en attente de la
-- scolarité. On recrée la contrainte CHECK pour l'autoriser (cf. V18).
ALTER TABLE seances DROP CONSTRAINT IF EXISTS seances_statut_check;
ALTER TABLE seances ADD CONSTRAINT seances_statut_check
    CHECK ((statut)::text = ANY ((ARRAY[
        'A_FAIRE'::character varying,
        'EMARGE'::character varying,
        'EN_RETARD'::character varying,
        'EN_ATTENTE_VALIDATION'::character varying,
        'A_VALIDER'::character varying
    ])::text[]));

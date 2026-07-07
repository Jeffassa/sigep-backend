-- SA-1 : nouveau rôle ROLE_SUPER_ADMIN (propriétaire de la plateforme, espace /plateforme).
-- La contrainte CHECK héritée de la baseline (V1) n'autorisait que ROLE_ADMIN et
-- ROLE_ENSEIGNANT : on l'élargit, sinon l'insertion du rôle au démarrage échoue.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_name_check;
ALTER TABLE roles ADD CONSTRAINT roles_name_check
    CHECK (((name)::text = ANY ((ARRAY['ROLE_ADMIN'::character varying,
                                       'ROLE_ENSEIGNANT'::character varying,
                                       'ROLE_SUPER_ADMIN'::character varying])::text[])));

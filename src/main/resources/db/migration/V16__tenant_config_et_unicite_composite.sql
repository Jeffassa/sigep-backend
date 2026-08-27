-- =====================================================================
-- V16 — Fondations multi-tenant : unicité PAR ÉTABLISSEMENT + colonnes
--        de configuration/branding/fuseau propres à chaque tenant.
--
-- Corrige :
--   C2  : unicité GLOBALE de matricule / salle.libelle / classe.libelle /
--         matiere.libelle → re-scopée en (etablissement_id, ...).
--   E1  : fuseau horaire par établissement (calculs d'émargement/crons).
--   E7  : tolérances d'émargement (avant/après) paramétrables par tenant.
--   E9  : identité e-mail (expéditeur / reply-to) par établissement.
--   E11 : numéro Mobile Money d'encaissement par établissement.
--   E13 : branding (logo, couleur, nom affiché) + F5 (locale, type).
--   C4  : clé kiosque QR par établissement (remplace la variable globale).
-- =====================================================================

-- ---------------------------------------------------------------------
-- C2 — Unicité composite (etablissement_id, ...) au lieu de globale.
-- Les contraintes globales du baseline (noms générés par Hibernate) sont
-- retirées ; DROP IF EXISTS pour rester idempotent quel que soit le nom.
-- ---------------------------------------------------------------------

-- Enseignants : matricule unique PAR établissement (pas globalement).
ALTER TABLE public.enseignants DROP CONSTRAINT IF EXISTS ukf2mtugyb88xrnvy1ktrslksxm;
ALTER TABLE public.enseignants
    ADD CONSTRAINT uk_enseignant_etab_matricule UNIQUE (etablissement_id, matricule);

-- Salles : libellé unique PAR établissement.
ALTER TABLE public.salles DROP CONSTRAINT IF EXISTS ukptuke9exnl35j1l2pajbno6x5;
ALTER TABLE public.salles
    ADD CONSTRAINT uk_salle_etab_libelle UNIQUE (etablissement_id, libelle);

-- Classes / Matières : aucune contrainte d'unicité n'existait en base
-- (le @Column(unique=true) des entités était inerte sous ddl-auto=validate).
-- On matérialise l'unicité au bon niveau : par établissement.
ALTER TABLE public.classes
    ADD CONSTRAINT uk_classe_etab_libelle UNIQUE (etablissement_id, libelle);
ALTER TABLE public.matieres
    ADD CONSTRAINT uk_matiere_etab_libelle UNIQUE (etablissement_id, libelle);

-- ---------------------------------------------------------------------
-- Colonnes de configuration par établissement.
-- Valeurs par défaut = comportement historique (ESATIC / UTC+0) pour ne
-- rien changer aux tenants existants.
-- ---------------------------------------------------------------------
ALTER TABLE public.etablissements
    ADD COLUMN IF NOT EXISTS fuseau                   VARCHAR(64)  NOT NULL DEFAULT 'Africa/Abidjan',
    ADD COLUMN IF NOT EXISTS locale                   VARCHAR(10)  NOT NULL DEFAULT 'fr',
    ADD COLUMN IF NOT EXISTS type_etablissement       VARCHAR(40)  NOT NULL DEFAULT 'SUPERIEUR',
    ADD COLUMN IF NOT EXISTS tolerance_avant_minutes  INTEGER      NOT NULL DEFAULT 15,
    ADD COLUMN IF NOT EXISTS tolerance_apres_minutes  INTEGER      NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS nom_affiche              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS logo_url                 VARCHAR(512),
    ADD COLUMN IF NOT EXISTS couleur_principale       VARCHAR(9),
    ADD COLUMN IF NOT EXISTS email_from               VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email_reply_to           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mobile_money_numero      VARCHAR(40),
    ADD COLUMN IF NOT EXISTS kiosk_key                VARCHAR(64);

-- Clé kiosque QR unique par établissement pour les tenants déjà existants.
UPDATE public.etablissements
   SET kiosk_key = replace(gen_random_uuid()::text, '-', '')
 WHERE kiosk_key IS NULL;

-- Contrôle des valeurs de tolérance (défense en profondeur).
ALTER TABLE public.etablissements
    ADD CONSTRAINT chk_etab_tolerances
    CHECK (tolerance_avant_minutes >= 0 AND tolerance_apres_minutes >= 0);

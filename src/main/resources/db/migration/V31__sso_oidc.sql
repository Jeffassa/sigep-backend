-- SSO OpenID Connect par établissement.
--
-- « SSO » figurait dans l'offre Enterprise depuis le premier jour, et Feature.SSO n'était
-- consultée nulle part — exactement la situation de l'API publique avant la V30.
--
-- Ce que cette table porte : les coordonnées du fournisseur d'identité d'UN établissement.
-- Une ligne par établissement, et pas davantage : deux fournisseurs pour un même tenant
-- rouvriraient la question « lequel fait foi » à chaque connexion.
CREATE TABLE IF NOT EXISTS configurations_sso (
    id                   BIGSERIAL PRIMARY KEY,
    -- UNIQUE : un établissement, un fournisseur d'identité.
    etablissement_id     BIGINT       NOT NULL UNIQUE REFERENCES etablissements (id),

    -- Nom affiché sur le bouton de connexion (« Google Workspace », « Microsoft Entra ID »).
    -- Purement cosmétique : l'autorité, c'est l'émetteur ci-dessous.
    fournisseur          VARCHAR(80)  NOT NULL,

    -- URL de l'émetteur OIDC. Tout le reste — points d'accès, clés de signature — en est
    -- découvert (/.well-known/openid-configuration). On ne recopie donc aucune URL à la main,
    -- et une rotation de clés chez le fournisseur ne nous demande rien.
    issuer               VARCHAR(400) NOT NULL,

    client_id            VARCHAR(255) NOT NULL,

    -- Le secret client est CHIFFRÉ (AES-256-GCM), pas haché : il faut le relire pour appeler
    -- le point d'accès « token » du fournisseur. Une sauvegarde de base qui fuite ne livre
    -- donc rien d'utilisable sans la clé, qui vit en variable d'environnement et jamais ici.
    client_secret        TEXT         NOT NULL,

    -- Domaines de courriel acceptés, séparés par des virgules (« esatic.ci,etu.esatic.ci »).
    -- Vide = aucune restriction de domaine. Deuxième verrou après le rattachement du compte :
    -- un fournisseur mal configuré qui accepterait des comptes personnels ne ferait pas
    -- entrer gmail.com dans un établissement.
    domaines             VARCHAR(500),

    -- Interrupteur : coupe le SSO sans perdre la configuration (le jour où le fournisseur
    -- tombe en panne, il faut pouvoir revenir au mot de passe en une case à cocher).
    actif                BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Faut-il EN PLUS exiger notre code à six chiffres ?
    -- Par défaut non : un établissement qui délègue l'authentification à son fournisseur y
    -- applique sa propre politique de second facteur, et lui réclamer un code par courriel
    -- par-dessus reviendrait à ne rien lui vendre. Le jour où ce fournisseur n'impose aucun
    -- second facteur, cette case le rétablit.
    exiger_otp           BOOLEAN      NOT NULL DEFAULT FALSE,

    cree_le              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modifie_le           TIMESTAMP,
    derniere_connexion   TIMESTAMP
);

-- Lu à chaque tentative de connexion SSO, par établissement.
CREATE INDEX IF NOT EXISTS idx_configurations_sso_etablissement
    ON configurations_sso (etablissement_id);

COMMENT ON COLUMN configurations_sso.client_secret IS
    'Chiffré AES-256-GCM avec APP_SSO_SECRET_KEY. Jamais affiché, jamais journalisé.';
COMMENT ON COLUMN configurations_sso.domaines IS
    'Domaines de courriel acceptés, séparés par des virgules. Vide = pas de restriction.';
COMMENT ON COLUMN configurations_sso.exiger_otp IS
    'TRUE = le code à six chiffres reste exigé même après une connexion SSO.';

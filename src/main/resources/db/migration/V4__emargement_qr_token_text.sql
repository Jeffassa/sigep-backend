-- Le token QR universel (JWT) dépasse 255 caractères : la colonne d'audit du token
-- scanné doit être TEXT (sinon "value too long for type character varying(255)").
ALTER TABLE emargements ALTER COLUMN qr_token_utilise TYPE TEXT;

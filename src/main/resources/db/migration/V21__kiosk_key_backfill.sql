-- C4 : garantir une clé kiosque à CHAQUE établissement (autorise l'affichage du QR d'émargement).
-- Les tenants créés avant C4 (établissement par défaut, plateforme, legacy) n'avaient pas de
-- kiosk_key → l'écran d'émargement /api/qr/display renvoyait 403 (fail-closed), impossible à ouvrir.
-- On backfille une clé aléatoire (UUID sans tirets) pour ceux qui en manquent. Idempotent.
UPDATE etablissements
   SET kiosk_key = replace(gen_random_uuid()::text, '-', '')
 WHERE kiosk_key IS NULL OR kiosk_key = '';

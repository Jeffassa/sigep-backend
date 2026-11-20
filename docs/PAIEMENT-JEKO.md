# Paiement des abonnements par Jèko

Les établissements paient leur abonnement par Mobile Money (Wave, Orange Money, MTN MoMo,
Moov Money, Djamo) sur la page de paiement sécurisée de Jèko. SIGEP ne voit ni le numéro ni les
codes du payeur : il reçoit seulement la confirmation.

> **Jèko n'a pas de bac à sable.** Toute demande créée avec les clés porte sur de l'argent réel.

## Acheter depuis la page d'accueil, sans assistance

Un nouveau client n'a besoin de personne :

1. Sur le bordereau de l'accueil, il clique sur **Souscrire au plan Pro** (ou Enterprise).
2. Il remplit l'inscription habituelle — aucun prix ni durée à cette étape — et clique sur
   **Créer mon espace et continuer** : l'espace est créé, en attente.
3. La **page de paiement** (`/inscription/payer`) lui présente les offres, la durée (1, 3 ou
   12 mois), le moyen de paiement et le total. **Payer** l'envoie sur la page de Jèko.
4. Dès que Jèko confirme, l'espace est **activé automatiquement** : plus d'examen pour qui a payé.
   Il reçoit l'e-mail « Votre espace est activé » et peut se connecter.

La page de paiement s'ouvre avec un jeton signé, valable 30 jours, qui ne donne accès qu'au
paiement de cet établissement. Le lien est envoyé par e-mail à l'inscription, pour payer plus
tard. Après Jèko, le client revient sur `/inscription/paiement?ref=JK-…`, qui suit le paiement ;
après un échec, **Payer à nouveau** le ramène à la page de paiement. Il peut aussi changer de
durée ou de moyen après avoir quitté la page de Jèko : une nouvelle demande est créée (si les
deux étaient payées, les deux seraient créditées).

- L'inscription **gratuite** reste examinée par la plateforme, comme avant.
- Sans Jèko configuré, « Souscrire » mène à une inscription ordinaire : l'espace est créé, le
  client passe au plan payant depuis son espace une fois validé.
- Un dossier **refusé** n'est jamais rouvert par un paiement : l'argent est enregistré et signalé
  dans les journaux (« dossier est REFUSÉ »), la décision reste à la plateforme.
- Un espace en attente dont le paiement n'aboutit jamais reste dans « Inscriptions à valider » du
  tableau de bord de la plateforme.

## Le parcours depuis l'espace d'un établissement

1. Sur **Abonnement**, l'administrateur choisit le plan, la durée et son moyen de paiement.
2. SIGEP crée la demande chez Jèko (montant recalculé côté serveur) et l'envoie sur la page Jèko.
3. Il paie, puis revient sur SIGEP.
4. SIGEP lit l'état du paiement chez Jèko. S'il est payé, au bon montant, l'abonnement est
   prolongé ; sinon, il attend ou signale l'échec.

Trois chemins mènent à la vérification, et un paiement n'est jamais crédité deux fois :

| Chemin | Rôle |
|---|---|
| Retour de l'administrateur | Confirmation immédiate |
| Notification de Jèko (webhook) | Confirmation même si l'administrateur ferme la page |
| Relance automatique (chaque minute) | Rattrape tout le reste, et les échecs : Jèko n'envoie **aucune** notification pour un paiement échoué |

Un paiement sans verdict après 48 heures passe « à vérifier » sur le tableau de bord de la
plateforme : il faut alors le contrôler dans Jèko Business.

## Mise en service

### 1. Dans Jèko Business (business.jeko.africa)

1. **Vérifier le compte** : sans le niveau « Création du compte », l'API répond « compte non
   autorisé ». Le plafond d'encaissement dépend aussi de ce niveau (200 000 F au niveau 0).
2. **Magasins** : copier l'identifiant du magasin qui recevra les abonnements.
3. **Paramètres → API & Webhooks → Créer une clé API** : copier la **clé** et son **ID**.
4. **Paramètres → API & Webhooks → Webhooks par magasin → Ajouter** : choisir le magasin et
   l'adresse `https://sigep.store/api/paiement/jeko/webhook`, puis **copier le secret affiché** :
   il ne sera plus jamais montré.

### 2. Sur Render (service SIGEP → Environment)

| Variable | Valeur |
|---|---|
| `JEKO_ENABLED` | `true` |
| `JEKO_API_KEY` | la clé API |
| `JEKO_API_KEY_ID` | l'ID de la clé |
| `JEKO_STORE_ID` | l'identifiant du magasin |
| `JEKO_WEBHOOK_SECRET` | le secret du webhook |

Enregistrer et redéployer. La page **Abonnement** propose alors « Payer avec » Wave, Orange
Money, MTN MoMo, Moov Money ou Djamo. Si NovaSend est aussi configuré, Jèko est proposé à sa place.

### 3. Premier essai

Faire un vrai paiement d'un mois depuis un établissement de test, puis vérifier :

- sur SIGEP : le bandeau « paiement confirmé » et la nouvelle date d'échéance ;
- dans Jèko Business : la transaction, et la livraison du webhook ;
- dans les journaux Render : la ligne `Notification Jèko : ref=JK-… statut=success`.

## À savoir

- **Webhook désactivé après 15 échecs consécutifs**, sans alerte, et jamais réactivé seul : il
  faut le supprimer et le recréer dans Jèko Business. Les paiements restent confirmés par la
  relance automatique, en une minute au lieu d'une seconde.
- **Frais** : 1,5 % par paiement réussi, prélevés par Jèko.
- **Montant** : Jèko compte en centimes ; SIGEP envoie `montant × 100`.
- **Références** : chaque paiement SIGEP porte une référence `JK-…`. Les autres paiements reçus
  par le magasin sont ignorés.
- **Retour arrière** : `JEKO_ENABLED=false` retire Jèko de la page ; les paiements déjà lancés
  restent suivis jusqu'à leur verdict, tant que les clés restent en place.

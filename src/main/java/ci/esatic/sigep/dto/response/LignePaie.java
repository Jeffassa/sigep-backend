package ci.esatic.sigep.dto.response;

/**
 * Une ligne d'export pour la paie : ce qu'un enseignant a fait sur une période.
 *
 * <p>Quatre blocs, et l'ordre n'est pas décoratif. D'abord <b>qui</b> (le matricule vient en
 * premier : c'est la clé sur laquelle le service paie rapproche ses propres fiches). Ensuite
 * <b>ce qui est prouvé</b> — les séances émargées et les heures correspondantes, le seul chiffre
 * qu'on puisse payer les yeux fermés. Puis <b>ce qui attend une décision humaine</b> : les
 * séances terminées qu'aucun émargement ne couvre, celles déclarées hors-ligne qu'un
 * administrateur n'a pas encore confirmées, et celles dont les horaires ne veulent rien dire.
 * Enfin <b>ce qui n'a simplement pas encore eu lieu</b>.
 *
 * <p>Le troisième bloc est la raison d'être du format. Une séance oubliée au scan n'est pas une
 * séance non faite : la retirer silencieusement de la paie ferait porter un incident technique
 * par l'enseignant. On l'affiche donc à part, avec les heures en jeu, pour que quelqu'un
 * tranche en connaissance de cause.
 *
 * <p>Le quatrième existe pour ne pas noyer le troisième. Un export lancé le 28 pour le mois en
 * cours trouve encore trois jours de cours au planning : les compter comme des manquements
 * ferait remonter tout l'établissement en tête du tableau, et le vrai oubli de scan
 * disparaîtrait dans le bruit.
 *
 * <p>Aucun montant ici, et c'est délibéré : SIGEP ne connaît pas le taux horaire d'un
 * enseignant. Il dépend du grade, de l'ancienneté, parfois d'une convention, et rien de tout
 * cela n'existe dans nos données. Un montant calculé serait un chiffre inventé sur une fiche
 * de paie réelle.
 */
public record LignePaie(
        String matricule,
        String nom,
        String prenom,
        String grade,
        String departement,

        /** Total des séances planifiées sur la période, tous statuts confondus. */
        long seancesPrevues,

        /** Séances émargées et validées : la base de la rémunération. */
        long seancesEmargees,

        /** Heures correspondant aux séances émargées, arrondies au centième. */
        double heures,

        /** Émargements arrivés après la fin de la séance. N'ôtent aucune heure : information. */
        long retards,

        /** Émargements enregistrés sans QR de salle. Signalés parce qu'ils reposent sur la parole. */
        long horsLigne,

        /** Séances déclarées hors-ligne, en attente de confirmation par un administrateur. */
        long seancesEnAttente,

        /** Heures suspendues à cette confirmation. */
        double heuresEnAttente,

        /** Séances TERMINÉES qu'aucun émargement ne couvre. */
        long seancesNonEmargees,

        /** Heures en jeu si ces séances devaient finalement être payées. */
        double heuresNonEmargees,

        /** Séances encore à venir au moment de l'export : ni faites, ni manquées. */
        long seancesAVenir,

        /** Heures que ces séances représenteront une fois données. */
        double heuresAVenir,

        /**
         * Séances dont les horaires ne permettent aucun calcul : heure de fin égale ou
         * antérieure au début sans franchissement de minuit plausible, ou durée absurde.
         *
         * <p>Elles ne sont comptées nulle part ailleurs — ni payées, ni portées au débit de
         * l'enseignant. Les faire disparaître aurait été plus simple, et c'est exactement le
         * défaut que ce fichier existe pour éviter : des heures qui s'évaporent sans que
         * personne ne l'apprenne.
         */
        long seancesDureeIncoherente
) {

    public String nomComplet() {
        return (prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom);
    }

    /**
     * Une ligne qui n'appelle aucun arbitrage.
     *
     * <p>Les séances à venir n'entrent pas dans ce jugement : elles ne demandent rien à
     * personne, elles n'ont pas encore eu lieu.
     */
    public boolean estNette() {
        return seancesNonEmargees == 0 && seancesEnAttente == 0 && seancesDureeIncoherente == 0;
    }

    /** Les heures suspendues à une décision : en attente de validation, ou sans émargement. */
    public double heuresAArbitrer() {
        return Math.round((heuresEnAttente + heuresNonEmargees) * 100) / 100.0;
    }
}

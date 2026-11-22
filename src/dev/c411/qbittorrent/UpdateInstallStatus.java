package dev.c411.qbittorrent;

/** What the system reports about an installation (the status of PackageInstaller), as what the app does next and what it tells the user. */
final class UpdateInstallStatus {
    /** What a status means for the app. */
    enum Kind {
        /** The system needs the user to confirm: the intent that comes with the status shows the dialog. */
        PENDING,
        SUCCESS,
        /** The user said no, or the dialog was closed. */
        CANCELLED,
        FAILED
    }

    private UpdateInstallStatus() {}

    static Kind kind(int status) {
        switch (status) {
            case -1: return Kind.PENDING;
            case 0: return Kind.SUCCESS;
            case 3: return Kind.CANCELLED;
            default: return Kind.FAILED;
        }
    }

    /** A French sentence for a status that is not a success; the message of the system is not used, it is in English and may name paths. */
    static String message(int status) {
        switch (status) {
            case 1: return "L’installation a échoué";
            case 2: return "Installation bloquée par le système (Play Protect ou contrôle parental)";
            case 3: return "Installation annulée";
            case 4: return "Le fichier d’installation est invalide";
            case 5: return "Conflit avec la version installée (signature différente)";
            case 6: return "Espace de stockage insuffisant";
            case 7: return "Mise à jour incompatible avec cet appareil";
            case 8: return "L’installation a expiré";
            default: return "L’installation a échoué (code " + status + ")";
        }
    }
}

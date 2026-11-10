package app.unscroll.prefs

import android.content.Context

/**
 * Une saisie juste ouvre les modifications de limites pendant quelques minutes.
 * Quitter l’application referme. Le choix d’un fichier garde la session le temps du sélecteur.
 */
object EditLock {
    private const val HOLD_MS = 3 * 60 * 1000L
    private const val DOCUMENT_MS = 2 * 60 * 1000L

    @Volatile
    private var untilMillis = 0L

    @Volatile
    private var documentUntil = 0L

    fun allows(context: Context): Boolean {
        if (!PinGate.isEnabled(context)) return true
        return System.currentTimeMillis() < untilMillis
    }

    fun unlock() {
        untilMillis = System.currentTimeMillis() + HOLD_MS
    }

    fun lock() {
        untilMillis = 0L
        documentUntil = 0L
    }

    fun holdForDocument() {
        documentUntil = System.currentTimeMillis() + DOCUMENT_MS
    }

    fun releaseDocument() {
        documentUntil = 0L
    }

    /** Ne referme pas pendant un sélecteur de fichier encore ouvert. */
    fun lockIfIdle() {
        if (System.currentTimeMillis() < documentUntil) return
        untilMillis = 0L
    }
}

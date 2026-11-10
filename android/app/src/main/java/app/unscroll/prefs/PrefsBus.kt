package app.unscroll.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Incrémente lorsque le thème doit être relu. */
object PrefsBus {
    private val ticks = MutableStateFlow(0)
    val revision: StateFlow<Int> = ticks.asStateFlow()

    fun bump() {
        ticks.update { it + 1 }
    }
}

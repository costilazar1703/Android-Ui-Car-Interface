package ro.e92.launcher.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cardul de next-turn de pe home, alimentat din notificarea persistentă a aplicației
 * de navigație. Costul marginal e zero: NotificationListenerService era oricum
 * necesar pentru media.
 *
 * Object singleton pentru că serviciul de notificări are ciclu de viață propriu,
 * independent de Activity, iar starea trebuie să-i supraviețuiască.
 */
object NavNotificationRelay {

    data class Guidance(val primary: String?, val secondary: String?) {
        val isEmpty: Boolean get() = primary.isNullOrBlank() && secondary.isNullOrBlank()
    }

    private val _guidance = MutableStateFlow(Guidance(null, null))
    val guidance: StateFlow<Guidance> = _guidance.asStateFlow()

    fun publish(primary: String?, secondary: String?) {
        _guidance.value = Guidance(primary, secondary)
    }

    fun clear() {
        _guidance.value = Guidance(null, null)
    }
}

package ro.e92.launcher.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ro.e92.launcher.R

/**
 * Cele 10 destinații ale meniului principal.
 *
 * Enum, nu String: fiecare intrare TREBUIE tratată în [ScreenHost.openMenu], iar
 * `when`-ul exhaustiv din HomeActivity nu compilează dacă adaugi una și uiți de ea.
 */
enum class MainMenuAction {
    NAVIGATION,
    CAR_INFO,
    SETTINGS,
    MEDIA,
    BLUETOOTH,
    TELEPHONE,
    CARPLAY,
    DASHBOARD,
    CONNECTED_DRIVE,
    MESSAGES
}

/**
 * O intrare de meniu, așa cum e desenată pe card.
 *
 * [id] e stabil și e cheia folosită de [ro.e92.launcher.focus.FocusEngine] pentru
 * restaurarea focusului după BACK — de aceea e un String scris de mână, nu
 * `action.name` sau indexul: ordinea din catalog se poate schimba, id-ul nu.
 */
class MenuItem(
    val id: String,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    val action: MainMenuAction
)

/**
 * Sursa unică de adevăr pentru meniul principal.
 *
 * Ordinea de aici e ordinea de pe ecran ȘI ordinea de rotire a controller-ului
 * iDrive: [ro.e92.launcher.ui.screens.HomeScreen] nu re-sortează nimic, doar
 * împarte lista în pagini de câte [PAGE_SIZE]. Paginarea e o consecință a
 * indexului focusat, nu o stare separată — de aici funcția [pageOf].
 */
object MenuCatalog {

    /** Cinci rânduri pe pagină: pe 480 px verticali, al șaselea ar fi ilizibil. */
    const val PAGE_SIZE = 5

    val items: List<MenuItem> = listOf(
        // ---- pagina 1 ----
        MenuItem("nav", R.drawable.ic_menu_navigation, R.string.menu_navigation, MainMenuAction.NAVIGATION),
        MenuItem("carinfo", R.drawable.ic_menu_car_info, R.string.menu_car_info, MainMenuAction.CAR_INFO),
        MenuItem("settings", R.drawable.ic_menu_settings, R.string.menu_settings, MainMenuAction.SETTINGS),
        MenuItem("media", R.drawable.ic_menu_media, R.string.menu_media, MainMenuAction.MEDIA),
        MenuItem("bt", R.drawable.ic_menu_bluetooth, R.string.menu_bluetooth, MainMenuAction.BLUETOOTH),
        // ---- pagina 2 ----
        MenuItem("tel", R.drawable.ic_menu_telephone, R.string.menu_telephone, MainMenuAction.TELEPHONE),
        MenuItem("carplay", R.drawable.ic_menu_carplay, R.string.menu_carplay, MainMenuAction.CARPLAY),
        MenuItem("dash", R.drawable.ic_menu_dashboard, R.string.menu_dashboard, MainMenuAction.DASHBOARD),
        MenuItem("cd", R.drawable.ic_menu_connecteddrive, R.string.menu_connecteddrive, MainMenuAction.CONNECTED_DRIVE),
        MenuItem("msg", R.drawable.ic_menu_messages, R.string.menu_messages, MainMenuAction.MESSAGES)
    )

    /** Rotunjit în sus: o pagină parțială e tot o pagină. */
    val pageCount: Int = (items.size + PAGE_SIZE - 1) / PAGE_SIZE

    fun pageOf(index: Int): Int = index / PAGE_SIZE

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }
}

package ro.e92.launcher.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ro.e92.launcher.R

/**
 * Cele 12 dale ale ecranului principal, stil BMW EVO ID5/ID6.
 *
 * Structura pe două niveluri, ca în mașină:
 *
 *   nivel 1 — [TileCatalog]      6 dale pe pagină, 2 pagini
 *   nivel 2 — [ro.e92.launcher.ui.screens.WheelMenuScreen]  rotița + lista
 *   nivel 3 — [ro.e92.launcher.ui.screens.SplitScreen]      categorii + detaliu
 *
 * Apăsarea unei dale NU lansează direct nimic: deschide meniul dalei. Excepțiile
 * sunt marcate în [TileAction] și există pentru că un meniu cu un singur rând ar
 * fi un clic în plus fără niciun câștig.
 */
enum class TileAction {
    NAVIGATION,
    MEDIA,
    TELEPHONE,
    BLUETOOTH,
    CAR_INFO,
    DASHBOARD,
    CARPLAY,
    CONNECTED_DRIVE,
    WEATHER,
    MESSAGES,
    APPS,
    SETTINGS
}

/**
 * O dală. [icon] e desenul din pătrat, [title] eticheta de deasupra lui —
 * în ID6 textul stă peste dală, nu în ea.
 */
class Tile(
    val id: String,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    val action: TileAction
)

object TileCatalog {

    /**
     * Șase pe pagină. Pe 1280x480, cu bara de sus scăzută, o dală iese la
     * ~195x250 px — aproape de proporția din ID6, unde dalele sunt puțin mai
     * înalte decât late. A șaptea ar coborî sub 170 px lățime, iar eticheta de
     * deasupra ar începe să se rupă pe două rânduri.
     */
    const val PAGE_SIZE = 6

    val items: List<Tile> = listOf(
        // ------------------------------ pagina 1 ------------------------------
        Tile("nav", R.drawable.ic_menu_navigation, R.string.menu_navigation, TileAction.NAVIGATION),
        Tile("media", R.drawable.ic_menu_media, R.string.menu_media, TileAction.MEDIA),
        Tile("tel", R.drawable.ic_menu_telephone, R.string.menu_telephone, TileAction.TELEPHONE),
        Tile("bt", R.drawable.ic_menu_bluetooth, R.string.menu_bluetooth, TileAction.BLUETOOTH),
        Tile("carinfo", R.drawable.ic_tile_e92, R.string.menu_car_info, TileAction.CAR_INFO),
        Tile("dash", R.drawable.ic_menu_dashboard, R.string.menu_dashboard, TileAction.DASHBOARD),

        // ------------------------------ pagina 2 ------------------------------
        Tile("carplay", R.drawable.ic_menu_carplay, R.string.menu_carplay, TileAction.CARPLAY),
        Tile("cd", R.drawable.ic_menu_connecteddrive, R.string.menu_connecteddrive, TileAction.CONNECTED_DRIVE),
        Tile("weather", R.drawable.ic_menu_weather, R.string.menu_weather, TileAction.WEATHER),
        Tile("msg", R.drawable.ic_menu_messages, R.string.menu_messages, TileAction.MESSAGES),
        Tile("apps", R.drawable.ic_menu_apps, R.string.menu_apps, TileAction.APPS),
        Tile("settings", R.drawable.ic_menu_settings, R.string.menu_settings, TileAction.SETTINGS)
    )

    val pageCount: Int = (items.size + PAGE_SIZE - 1) / PAGE_SIZE

    fun pageOf(index: Int): Int = index / PAGE_SIZE

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }
}

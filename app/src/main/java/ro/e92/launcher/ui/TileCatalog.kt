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
    val action: TileAction,
    /**
     * Multiplicator peste marimea standard a iconitei.
     *
     * Exista pentru o singura problema, masurata nu presupusa: o iconita e
     * desenata intr-o caseta PATRATA, iar greutatea ei vizuala vine din cat din
     * patratul ala umple. Celelalte unsprezece umplu 69-82%, fiind siluete
     * aproximativ patrate. Masina e de 1.8 ori mai lata decat inalta, deci
     * umple latimea dar doar jumatate din inaltime - 47%, cel mai putin din tot
     * setul, si se vedea imediat in rand.
     *
     * O masina nu va egala niciodata un cerc la aceeasi latime; singura cale e
     * sa i se dea mai multa latime. Latimea ramane plafonata la corpul dalei,
     * deci nu poate iesi din rama.
     */
    val iconScale: Float = 1f
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
        // ---- pagina 1: ce se atinge cel mai des in mers ----
        Tile("carplay", R.drawable.ic_menu_carplay, R.string.menu_carplay, TileAction.CARPLAY),
        Tile("carinfo", R.drawable.ic_menu_car_info, R.string.menu_car_info, TileAction.CAR_INFO, iconScale = 1.55f),
        Tile("bt", R.drawable.ic_menu_bluetooth, R.string.menu_bluetooth, TileAction.BLUETOOTH),
        Tile("dash", R.drawable.ic_menu_dashboard, R.string.menu_dashboard, TileAction.DASHBOARD),
        Tile("settings", R.drawable.ic_menu_settings, R.string.menu_settings, TileAction.SETTINGS),
        Tile("apps", R.drawable.ic_menu_apps, R.string.menu_apps, TileAction.APPS),

        // ---- pagina 2 ----
        Tile("nav", R.drawable.ic_menu_navigation, R.string.menu_navigation, TileAction.NAVIGATION),
        Tile("media", R.drawable.ic_menu_media, R.string.menu_media, TileAction.MEDIA),
        Tile("tel", R.drawable.ic_menu_telephone, R.string.menu_telephone, TileAction.TELEPHONE),
        Tile("cd", R.drawable.ic_menu_connecteddrive, R.string.menu_connecteddrive, TileAction.CONNECTED_DRIVE),
        Tile("weather", R.drawable.ic_menu_weather, R.string.menu_weather, TileAction.WEATHER),
        Tile("msg", R.drawable.ic_menu_messages, R.string.menu_messages, TileAction.MESSAGES)
    )

    val pageCount: Int = (items.size + PAGE_SIZE - 1) / PAGE_SIZE

    fun pageOf(index: Int): Int = index / PAGE_SIZE

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }
}

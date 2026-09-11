package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenDriveSportBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Salutul de mod de condus — „DRIVE / SPORT" — peste ecranul curent.
 *
 * Se comportă ca în mașinile noi: apare la schimbarea modului, stă cât să-l
 * citești și dispare singur, lăsându-te exact unde erai. De aceea e
 * [isOverlay]: ScreenStack păstrează ecranul de dedesubt vizibil, iar la
 * autoînchidere revii în el fără să se reconstruiască nimic.
 *
 * **Nu are ținte de focus.** Rotița nu trebuie să poată intra în el — e o
 * confirmare, nu un meniu. Orice tastă îl închide imediat, ca să nu stea în
 * calea nimănui dacă apare într-un moment prost.
 *
 * Animația e decalată intenționat: eticheta se așază, numele modului urcă peste
 * ea, linia se trage la final. Toate trei deodată ar arăta a notificare de
 * sistem, nu a mașină care confirmă o comandă.
 */
class DriveSportScreen(
    host: ScreenHost,
    @StringRes private val modeName: Int = R.string.drive_mode_sport
) : Screen(host) {

    override val title: String get() = context.getString(modeName).uppercase()

    override val isOverlay: Boolean get() = true

    private lateinit var binding: ScreenDriveSportBinding

    /** Închiderea se face o singură dată, oricâte drumuri ar duce la ea. */
    private var dismissed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenDriveSportBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        binding.sportName.setText(modeName)

        if (Services.prefs.animationsEnabled) {
            playIntro()
        } else {
            binding.sportRule.layoutParams.width = ruleWidth()
            binding.sportRule.requestLayout()
        }

        // Scope-ul e legat de vizibilitatea ecranului: dacă utilizatorul iese
        // singur mai devreme, numărătoarea moare odată cu el.
        screenScope.launch {
            delay(HOLD_MS)
            dismiss()
        }
    }

    /** Fără ținte: rotița nu are ce selecta aici. */
    override fun focusTargets(): List<FocusTarget> = emptyList()

    /**
     * Orice acțiune îl închide. Nu consumăm tasta — o lăsăm să ajungă la ecranul
     * de dedesubt, ca o apăsare care nimerește peste salut să nu se piardă.
     */
    override fun onAction(action: LauncherAction): Boolean {
        dismiss()
        return false
    }

    private fun playIntro() {
        val block = binding.sportBlock
        val kicker = binding.sportKicker
        val name = binding.sportName
        val rule = binding.sportRule

        kicker.alpha = 0f
        name.alpha = 0f
        name.translationY = NAME_RISE_PX
        rule.layoutParams.width = 0

        binding.sportScrim.alpha = 0f
        binding.sportScrim.animate().alpha(1f).setDuration(SCRIM_MS).start()

        kicker.animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        name.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(NAME_DELAY_MS)
            .setDuration(RISE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Linia se lățește din centru. Se animează LayoutParams, nu scaleX: un
        // scale pe un gradient i-ar întinde și degradeul, iar capetele stinse ar
        // deveni benzi.
        val target = ruleWidth()
        block.postDelayed({
            val start = System.currentTimeMillis()
            val step = object : Runnable {
                override fun run() {
                    val t = ((System.currentTimeMillis() - start).toFloat() / RULE_MS)
                        .coerceIn(0f, 1f)
                    val eased = 1f - (1f - t) * (1f - t)
                    rule.layoutParams.width = (target * eased).toInt()
                    rule.requestLayout()
                    if (t < 1f && !dismissed) rule.postOnAnimation(this)
                }
            }
            rule.postOnAnimation(step)
        }, RULE_DELAY_MS)
    }

    private fun ruleWidth(): Int {
        val w = binding.sportName.width
        return if (w > 0) w else context.resources.displayMetrics.widthPixels / 4
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        host.pop()
    }

    private companion object {
        /** Cât stă pe ecran din momentul apariției. */
        const val HOLD_MS = 2600L

        const val SCRIM_MS = 220L
        const val FADE_MS = 260L
        const val NAME_DELAY_MS = 120L
        const val RISE_MS = 340L
        const val RULE_DELAY_MS = 380L
        const val RULE_MS = 420f
        const val NAME_RISE_PX = 26f
    }
}

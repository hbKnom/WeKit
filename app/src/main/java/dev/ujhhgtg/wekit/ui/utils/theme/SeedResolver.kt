package dev.ujhhgtg.wekit.ui.utils.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamicColorScheme
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver.customSeed
import dev.ujhhgtg.wekit.utils.monet.MonetColors

/**
 * Single source of truth for turning [ThemeSettings] into a concrete accent seed and the derived
 * Material 3 color schemes. Shared by [ModuleTheme], [InjectedUiTheme], and
 * [dev.ujhhgtg.wekit.features.items.beautify.MonetEngine] so the module UI, the WeKit UI injected
 * into WeChat, and the native-view recoloring all agree on the same colors.
 */
object SeedResolver {

    private val wallpaperSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Platform wallpaper accent (primary), or `null` when unavailable (SDK < 31). */
    @SuppressLint("NewApi") // gated on [wallpaperSupported]
    private fun wallpaperAccent(context: Context, dark: Boolean): Int? {
        if (!wallpaperSupported) return null
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return scheme.primary.toArgb()
    }

    /** The platform wallpaper accent when enabled, otherwise the user's chosen seed color. */
    fun customSeed(context: Context, dark: Boolean): Int =
        if (ThemeSettings.dynamicWallpaper) wallpaperAccent(context, dark) ?: ThemeSettings.seedColor
        else ThemeSettings.seedColor

    /**
     * The seed for UI injected into WeChat: WeChat green unless the user opted the selected seed
     * into WeChat ([ThemeSettings.applyToWechat]), in which case it follows [customSeed].
     */
    fun injectedSeed(context: Context, dark: Boolean): Int =
        if (ThemeSettings.applyToWechat) customSeed(context, dark)
        else ThemeSettings.DEFAULT_SEED_COLOR

    /**
     * [InjectedUiTheme] 用的配色。
     *
     * 莫奈引擎生效时优先与微信原生取色 **同源**（引擎做的事就是把微信资源换成对这些 token 的
     * 引用）：安卓 12+ 直接用平台动态配色，更低版本用引擎色板当种子。这样「WeKit 注入微信界面
     * 的组件」（莫奈替换不到它们自己的资源 id）就不会和原生部分割裂。
     *
     * 引擎未生效时保持旧行为：微信绿，或 opt-in 之后跟随用户种子。
     */
    fun injectedScheme(context: Context, dark: Boolean): ColorScheme {
        val palette = MonetColors.applied.value
            ?: return if (ThemeSettings.applyToWechat) {
                materialScheme(customSeed(context, dark), dark)
            } else if (dark) {
                darkScheme
            } else {
                lightScheme
            }
        if (wallpaperSupported) {
            return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        return materialScheme(if (dark) palette.primaryDark else palette.primaryLight, dark)
    }

    /** Material 3 [ColorScheme] generated from [seed] with the current palette style + spec. */
    fun materialScheme(seed: Int, dark: Boolean): ColorScheme = dynamicColorScheme(
        seedColor = Color(seed),
        isDark = dark,
        style = ThemeSettings.paletteStyle.materialKolor,
        specVersion = ThemeSettings.effectiveColorSpec.materialKolor,
    )
}

package dev.ujhhgtg.wekit.utils.monet

import android.annotation.SuppressLint
import android.content.res.Resources
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.security.MessageDigest

/**
 * Turns a WeChat resource graph into everything the runtime package needs: the resolved role map,
 * the 16-colour [Palette] plus the authored [MonetOverlayPlan].
 *
 * Upstream 09-25 extracted this from `MonetModuleGenerator`, which had it entangled with the RRO
 * writer, the signer and the Magisk packer. The palette still comes from the *platform* dynamic
 * colours (`android.R.color.system_accent1_*`), so WeChat's replacement resources reference the same
 * android framework ids that Material You uses — that is what makes the recolouring track the
 * wallpaper without the engine ever reading the wallpaper itself.
 */
object MonetResourceResolver {

    private const val TAG = "MonetResourceResolver"

    /** Result of one analysis run over a WeChat build. */
    data class Resolution(
        val fingerprint: String,
        val bindings: MonetBindings,
        val resolved: Map<String, MonetResourceNode>,
        val palette: Palette,
        val plan: MonetOverlayPlan,
    )

    /**
     * Stable id for the analysed APK set: 版本号 + 每个 APK 的文件名 / 大小 / 修改时间。
     *
     * 这里**故意不读 APK 内容**：微信基础包 + 各 split 动辄数百 MB，旧实现逐个整包读入
     * 既分配整包内存、又在启动路径上做全量 I/O（用户要求「莫奈取色不影响微信流畅运行」）。
     * 版本号 + 每个 APK 的大小与 mtime 足以判定「微信是否被更新过」。
     */
    fun fingerprint(sourceApkPaths: List<String>, versionCode: Long, versionName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$versionCode/$versionName".toByteArray())
        sourceApkPaths.forEach { path ->
            val file = File(path)
            digest.update(path.substringAfterLast('/').toByteArray())
            digest.update(file.length().toString().toByteArray())
            digest.update(file.lastModified().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Resolves every semantic role and authors the replacement resources.
     *
     * @param resources host resources, used only to look up `android.R.color.system_*`.
     */
    fun resolve(
        graph: MonetResourceGraph,
        resources: Resources,
        fingerprint: String,
        bubbleStyle: MonetBubbleStyle,
        multiSceneCorners: Boolean,
        errorColors: Boolean = false,
        fallbackPalette: Palette? = null,
        dexProvider: MonetDexEvidenceProvider? = null,
        onProgress: (completed: Int?, total: Int?, detail: String) -> Unit = { _, _, _ -> },
    ): Resolution {
        val resolved = MonetStructureMatcher.resolveAll(graph, dexProvider) { completed, total, detail ->
            onProgress(completed, total, detail)
        }
        val palette = overlayPalette(resources, fallbackPalette)
        val colors = MONET_RULES
            .filter { it.type == "color" && it.id != MAIN_TAB_ROLE }
            .mapNotNull { rule ->
                val node = resolved[rule.id] ?: return@mapNotNull null
                val (light, night) = paletteFor(rule.id, resources)
                ColorTarget(node.binding(), light, night)
            }
        // 启动图标是可选的：旧实现用 requireNotNull，微信某次改动挪走 drawable/icon
        // 就足以让整次解析失败（用户看到的就是「解析出错」）。缺了就跳过这一张图。
        val splashIconId = graph.node(MonetResourceKey("drawable", "icon"))?.id ?: 0
        val authored = MonetAssetInjector.plan(
            resolved = resolved,
            palette = palette,
            style = bubbleStyle,
            multiSceneCorners = multiSceneCorners,
            splashIconId = splashIconId,
        )
        val plan = authored.copy(colors = colors)
        val unresolved = MonetStructureMatcher.roleIds - resolved.keys
        @Suppress("UNUSED_EXPRESSION") palette
        val bindings = MonetBindings(
            fingerprint = fingerprint,
            typeNames = resolved.values.map { it.key.type }.distinct().sorted(),
            roles = resolved.mapValues { (_, node) -> node.id },
            tints = paletteTints(resolved, palette, errorColors),
            unresolved = unresolved.sorted(),
        )
        WeLogger.i(
            TAG,
            "resolved ${resolved.size} roles (${plan.drawables.size} drawables, ${colors.size} colors, " +
                "${unresolved.size} unresolved)",
        )
        return Resolution(fingerprint, bindings, resolved, palette, plan)
    }

    private const val MAIN_TAB_ROLE = "main.tab.background"

    /**
     * Alpha overlays applied to WeChat's own translucent tokens. Only emitted when the user turned
     * the option on, because they fight with WeChat's built-in gradients otherwise.
     */
    private fun paletteTints(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        errorColors: Boolean,
    ): List<MonetTint> {
        if (!errorColors) return emptyList()
        return ERROR_TINT_ROLES.mapNotNull { role ->
            resolved[role]?.let { MonetTint(it.binding(), 0x12, 0x1f) }
        }
    }

    private val ERROR_TINT_ROLES = listOf(
        "chat.input.background",
        "chat.quote.background",
        "chat.input.transparent-layer",
    )

    /**
     * The 16 palette entries。优先用平台的 Material You token（安卓 12+：写进微信资源的是对
     * framework id 的引用，能实时跟随壁纸）；平台没提供时退回 WeKit 主题种子合成的色板
     * （安卓 11 以及部分 ROM —— `system_*` 从 API 31 才有，旧实现在这里直接抛错，
     * 于是整次解析失败）；再不行用内置基线色板。
     */
    @SuppressLint("DiscouragedApi")
    fun overlayPalette(resources: Resources, fallback: Palette? = null): Palette {
        frameworkPalette(resources)?.let { return it }
        if (fallback != null) {
            WeLogger.i(TAG, "平台未提供 Material You 取色，改用 WeKit 主题种子色板")
            return fallback
        }
        WeLogger.w(TAG, "平台未提供 Material You 取色且无备用色板，使用内置基线色板")
        return BASELINE_PALETTE
    }

    /** 平台 Material You 色板：任一必需 token 缺失都返回 null（不抛错）。 */
    @SuppressLint("DiscouragedApi")
    private fun frameworkPalette(resources: Resources): Palette? {
        if (!paletteAvailable(resources)) return null
        fun token(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
            resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        }
        return Palette(
            surfaceLight = token("system_surface_light") ?: return null,
            surfaceDark = token("system_surface_dark") ?: return null,
            surfaceContainerLight = token(
                "system_surface_container_light", "system_neutral2_50", "system_surface_light",
            ) ?: return null,
            surfaceContainerDark = token(
                "system_surface_container_dark", "system_neutral2_800", "system_surface_dark",
            ) ?: return null,
            surfaceContainerHighLight = token(
                "system_surface_container_high_light", "system_surface_container_light", "system_surface_light",
            ) ?: return null,
            surfaceContainerHighDark = token(
                "system_surface_container_high_dark", "system_surface_container_dark", "system_surface_dark",
            ) ?: return null,
            primaryLight = token("system_primary_light", "system_accent1_500") ?: return null,
            primaryDark = token("system_primary_dark", "system_accent1_200") ?: return null,
            primaryContainerLight = token("system_primary_container_light", "system_accent1_100") ?: return null,
            primaryContainerDark = token("system_primary_container_dark", "system_accent1_800") ?: return null,
            accent1_300 = token("system_accent1_300") ?: return null,
            accent1_400 = token("system_accent1_400") ?: return null,
            accent1_500 = token("system_accent1_500") ?: return null,
            accent1_700 = token("system_accent1_700") ?: return null,
            accent2_100 = token("system_accent2_100") ?: return null,
            neutral2_700 = token("system_neutral2_700", "system_surface_dark") ?: return null,
        )
    }

    /**
     * 把色板里的 framework 资源引用（`android.R.color.system_*` 的 id）解析成具体 ARGB，
     * 供 WeKit 自己注入微信界面的组件（不走资源替换，拿不到引用）取色。
     */
    @SuppressLint("DiscouragedApi")
    fun resolveArgb(resources: Resources, palette: Palette): Palette = Palette(
        surfaceLight = argb(resources, palette.surfaceLight),
        surfaceDark = argb(resources, palette.surfaceDark),
        surfaceContainerLight = argb(resources, palette.surfaceContainerLight),
        surfaceContainerDark = argb(resources, palette.surfaceContainerDark),
        surfaceContainerHighLight = argb(resources, palette.surfaceContainerHighLight),
        surfaceContainerHighDark = argb(resources, palette.surfaceContainerHighDark),
        primaryLight = argb(resources, palette.primaryLight),
        primaryDark = argb(resources, palette.primaryDark),
        primaryContainerLight = argb(resources, palette.primaryContainerLight),
        primaryContainerDark = argb(resources, palette.primaryContainerDark),
        accent1_300 = argb(resources, palette.accent1_300),
        accent1_400 = argb(resources, palette.accent1_400),
        accent1_500 = argb(resources, palette.accent1_500),
        accent1_700 = argb(resources, palette.accent1_700),
        accent2_100 = argb(resources, palette.accent2_100),
        neutral2_700 = argb(resources, palette.neutral2_700),
    )

    /**
     * 色板里可能是**字面 ARGB**（主题种子色板 / 基线色板，alpha 恒为 `0xFF`），也可能是
     * `android.R.color.system_*` 的**资源 id**。
     *
     * 判据只能看 alpha 字节：framework 资源 id 是 `0x01xxxxxx`（**不是** `0x00xxxxxx`），
     * 旧实现用 `value and 0xFF000000 == 0` 判断，于是安卓 12+ 的常规路径把资源 id 原样
     * 当成颜色发给了注入组件 —— 注入 UI 拿到「看着像颜色、其实是 id」的垃圾值，
     * 这正是用户反馈「微信原生已莫奈化、WeKit 组件还是旧配色」的直接原因之一。
     */
    @SuppressLint("DiscouragedApi")
    private fun argb(resources: Resources, value: Int): Int {
        if (value ushr 24 == 0xFF) return value
        return runCatching { resources.getColor(value, null) }.getOrDefault(value)
    }

    /** 最后兜底：Material You 基线色板（AOSP 默认紫）。 */
    private val BASELINE_PALETTE = Palette(
        surfaceLight = 0xFFFFFBFE.toInt(),
        surfaceDark = 0xFF1C1B1F.toInt(),
        surfaceContainerLight = 0xFFF3EDF7.toInt(),
        surfaceContainerDark = 0xFF211F26.toInt(),
        surfaceContainerHighLight = 0xFFECE6F0.toInt(),
        surfaceContainerHighDark = 0xFF2B2930.toInt(),
        primaryLight = 0xFF6750A4.toInt(),
        primaryDark = 0xFFD0BCFF.toInt(),
        primaryContainerLight = 0xFFEADDFF.toInt(),
        primaryContainerDark = 0xFF4F378B.toInt(),
        accent1_300 = 0xFFB69DF8.toInt(),
        accent1_400 = 0xFF9A82DB.toInt(),
        accent1_500 = 0xFF6750A4.toInt(),
        accent1_700 = 0xFF4F378B.toInt(),
        accent2_100 = 0xFFFFD8E4.toInt(),
        neutral2_700 = 0xFF49454F.toInt(),
    )

    /** Whether the platform exposes Material You colours at all (Android 12+). */
    @SuppressLint("DiscouragedApi")
    fun paletteAvailable(resources: Resources): Boolean =
        resources.getIdentifier("system_accent1_500", "color", "android") != 0

    /**
     * Resolves one `theme.color.<light>--<night>[.slot-NN]` rule id into the colour pair written into
     * the runtime package. Tokens are either a literal ARGB hex string, `unknown`, or a
     * `system-<role>` name from [overlayPalette].
     */
    @SuppressLint("DiscouragedApi")
    fun paletteFor(id: String, resources: Resources): Pair<ColorValue?, ColorValue?> {
        val semantic = id.removePrefix("theme.color.").substringBefore(".slot-")
        val parts = semantic.split("--", limit = 2)
        fun resolve(token: String): ColorValue? {
            if (token == "unknown") return null
            if (token.length == 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return runCatching { ColorValue.Literal(token.toUInt(16).toInt()) }.getOrNull()
            }
            if (!token.startsWith("system-")) {
                // 规则表里出现未知 token 时只放弃这一侧，不能让整次解析失败
                WeLogger.w(TAG, "unsupported Monet color token: $token")
                return null
            }
            val normalized = token.replace('-', '_')
            val fallbacks = when (normalized) {
                "system_surface_container_light" ->
                    listOf(normalized, "system_neutral2_50", "system_surface_light")
                "system_surface_container_dark" ->
                    listOf(normalized, "system_neutral2_800", "system_surface_dark")
                else -> listOf(normalized)
            }
            val id = frameworkColorId(resources, *fallbacks.toTypedArray()) ?: return null
            return ColorValue.Reference(id)
        }
        return resolve(parts.first()) to resolve(parts.getOrElse(1) { parts.first() })
    }

    /** 平台 token 的**资源 id**（写进替换资源里作为引用），取不到返回 null。 */
    @SuppressLint("DiscouragedApi")
    private fun frameworkColorId(resources: Resources, vararg names: String): Int? =
        names.firstNotNullOfOrNull { name ->
            resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        }
}

package dev.ujhhgtg.wekit.utils.monet

import kotlinx.serialization.Serializable

/**
 * Models shared by the Monet engine pipeline: WeChat resource bindings, the tinting plan and the
 * XML primitives used to author the replacement resources.
 *
 * Naming follows the upstream 09-25 runtime-injection rebuild — the RRO/module generator was
 * replaced by a runtime resource package that is fed straight into [android.content.res.loader.ResourcesLoader],
 * so the generation-only models live here next to the runtime ones and the serialisable binding
 * cache can be persisted across launches.
 */

/** One WeChat resource targeted by the engine: `id` is the resolved `0x7f…` resource id. */
@Serializable
data class MonetBinding(
    val id: Int,
    val name: String,
    val type: String,
    val qualifiers: List<String> = emptyList(),
    val specFlags: Int = 0,
)

/** How a binding should be tinted: full replacement, or an alpha overlay on a translucent token. */
@Serializable
data class MonetTint(
    val binding: MonetBinding,
    val lightAlpha: Int,
    val nightAlpha: Int? = null,
)

/**
 * The persistent result of analysing one WeChat build.
 *
 * [fingerprint] identifies the WeChat APK set (version + signature digest), so a cached binding set
 * is only reused while the analysed build is unchanged. [roles] maps a role name (see
 * `MonetSemanticRules`) to the resource id it resolved to; [unresolved] keeps the roles the matcher
 * could not anchor so the settings UI can report `resolved/total`.
 */
@Serializable
data class MonetBindings(
    val fingerprint: String,
    val typeNames: List<String> = emptyList(),
    val roles: Map<String, Int> = emptyMap(),
    val tints: List<MonetTint> = emptyList(),
    val unresolved: List<String> = emptyList(),
)

/** Stage of a WeChat resource analysis run, surfaced by the progress dialog. */
enum class MonetResolveStage(val label: String) {
    LOADING_APKS("loading APKs"),
    BUILDING_RESOURCE_GRAPH("building resource graph"),
    RESOLVING_ROLES("resolving roles"),
    BUILDING_PACKAGE("building resource package"),
}

/** Progress event emitted while analysing WeChat resources. */
data class MonetResolveProgress(
    val stage: MonetResolveStage,
    val detail: String,
    val completed: Int? = null,
    val total: Int? = null,
) {
    init {
        require(detail.isNotBlank())
        require((completed == null) == (total == null))
        if (completed != null && total != null) require(total > 0 && completed in 0..total)
    }
}

/** Outcome of one analysis run. */
sealed interface MonetResolveResult {
    data class Success(val bindings: MonetBindings, val runtimePackage: java.io.File?) : MonetResolveResult
    data class Failure(val progress: MonetResolveProgress, val message: String) : MonetResolveResult
}

/**
 * The 16-colour palette the overlays are built from. Values are ARGB ints; entries whose high byte is
 * `0x01` are resource references (`0x01rrggbb`) and are emitted as `@color/…` references rather than
 * literals, exactly like WeChat's own theme colours.
 */
data class Palette(
    val surfaceLight: Int,
    val surfaceDark: Int,
    val surfaceContainerLight: Int,
    val surfaceContainerDark: Int,
    val surfaceContainerHighLight: Int,
    val surfaceContainerHighDark: Int,
    val primaryLight: Int,
    val primaryDark: Int,
    val primaryContainerLight: Int,
    val primaryContainerDark: Int,
    val accent1_300: Int,
    val accent1_400: Int,
    val accent1_500: Int,
    val accent1_700: Int,
    val accent2_100: Int,
    val neutral2_700: Int,
)

/** Per-side padding applied to a generated `<shape>`. */
data class Padding(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Corner radii for a generated `<shape>`; use [circular] for a uniform radius. */
data class CornerRadius(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    companion object {
        fun circular(radius: Float) = CornerRadius(radius, radius, radius, radius)
    }
}

/** An authored binary-XML node to be written into the runtime resource package. */
data class XmlNode(
    val name: String,
    val attributes: List<XmlAttribute> = emptyList(),
    val children: List<XmlNode> = emptyList(),
)

/** An attribute of an authored [XmlNode]; [id] is the platform attribute resource id. */
data class XmlAttribute(val name: String, val id: Int, val value: XmlValue)

/** Typed attribute value understood by the resource writer. */
sealed interface XmlValue {
    data class Reference(val id: Int) : XmlValue
    data class NamedReference(val type: kotlin.String, val name: kotlin.String) : XmlValue
    data class Color(val argb: Int) : XmlValue
    data class Dimension(val dp: kotlin.Float) : XmlValue
    data class Integer(val value: Int) : XmlValue
    data class Boolean(val value: kotlin.Boolean) : XmlValue
    data class Float(val value: kotlin.Float) : XmlValue
    data class String(val value: kotlin.String) : XmlValue
}

/** A colour resource to be overridden in the runtime package. */
sealed interface ColorValue {
    data class Reference(val id: Int) : ColorValue
    data class Literal(val argb: Int) : ColorValue
}

data class ColorTarget(val binding: MonetBinding, val light: ColorValue?, val night: ColorValue?) {
    init { require(light != null || night != null) }
}

data class LiteralColorTarget(val binding: MonetBinding, val lightArgb: Int, val nightArgb: Int? = null)

data class StringTarget(val binding: MonetBinding, val value: String, val qualifiers: String = "")

/** A drawable resource to be replaced by an authored XML document, optionally per-qualifier. */
data class DrawableTarget(
    val binding: MonetBinding,
    val light: XmlNode,
    val night: XmlNode? = null,
    val lightQualifiers: String = "",
    val nightQualifiers: String = "-night",
)

/** Builds the [MonetBinding] for a resolved role. */
fun MonetResourceNode.binding(): MonetBinding =
    MonetBinding(id = id, name = key.name, type = key.type)

/** Everything the engine wants to override in one WeChat build. */
data class MonetOverlayPlan(
    val drawables: List<DrawableTarget> = emptyList(),
    val colors: List<ColorTarget> = emptyList(),
    val literalColors: List<LiteralColorTarget> = emptyList(),
    val strings: List<StringTarget> = emptyList(),
) {
    val isEmpty: Boolean
        get() = drawables.isEmpty() && colors.isEmpty() && literalColors.isEmpty() && strings.isEmpty()

    operator fun plus(other: MonetOverlayPlan) = MonetOverlayPlan(
        drawables + other.drawables,
        colors + other.colors,
        literalColors + other.literalColors,
        strings + other.strings,
    )
}

/** Bubble geometry preset authored by [MonetAssetInjector]. */
enum class MonetBubbleStyle { MODERN, CLASSIC, PRO }

/**
 * DEX-derived evidence models. Upstream 09-25 deleted the DexKit collector that produced these
 * (`MonetDexEvidenceCollector`), so the matcher now runs on the resource graph alone; the shape is
 * kept because [MonetStructureMatcher] still accepts an optional provider and because
 * `MonetSemanticRule.requiredDexEvidence` is preserved in the rule table.
 */
fun interface MonetDexEvidenceProvider {
    fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence>
}

data class MonetDexCandidate(val resourceId: Int, val type: String, val name: String)

data class MonetResourceDexEvidence(
    val resourceId: Int,
    val methods: List<MonetMethodDexEvidence>,
)

data class MonetMethodDexEvidence(
    val descriptor: String,
    val ownerPackage: String,
    val methodShape: String,
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetFieldAccessEvidence>,
)

data class MonetFieldAccessEvidence(val descriptor: String, val access: MonetFieldAccess)

enum class MonetFieldAccess { READ, WRITE }

// ---------------------------------------------------------------------------
// Resource-graph models (moved out of `MonetResourceGraph.kt` by the 09-25
// rebuild so every shared shape lives in one place).
// ---------------------------------------------------------------------------


data class MonetResourceKey(val type: String, val name: String) : java.io.Serializable

sealed interface MonetResourceValue : java.io.Serializable {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(val resourceId: Int, val valueType: String = "REFERENCE") : MonetResourceValue
    data class File(val path: String, val structure: MonetFileStructure?) : MonetResourceValue
    data class Text(val value: String) : MonetResourceValue
    data class Complex(val parentId: Int, val items: List<MonetComplexValue>) : MonetResourceValue
}

data class MonetFileStructure(
    val format: String,
    val width: Int? = null,
    val height: Int? = null,
    val colorType: Int? = null,
    val firstDataLength: Int? = null,
    val ninePatchLength: Int? = null,
    val sampleSum: Long? = null,
    val alphaSum: Long? = null,
    val distinctSamples: Int? = null,
    val pixelSha256: String? = null,
) : java.io.Serializable

data class MonetComplexValue(val nameId: Int, val value: MonetResourceValue) : java.io.Serializable
data class MonetConfiguredValue(val qualifiers: String, val value: MonetResourceValue) : java.io.Serializable
data class MonetResourceNode(
    val id: Int,
    val key: MonetResourceKey,
    val values: List<MonetConfiguredValue>,
) : java.io.Serializable

data class MonetXmlElement(
    val name: String,
    val namespace: String? = null,
    val attributes: List<MonetXmlAttribute>,
    val children: List<MonetXmlElement>,
) : java.io.Serializable

data class MonetXmlAttribute(
    val namespace: String?,
    val name: String,
    val nameId: Int?,
    val valueType: String,
    val value: MonetResourceValue,
) : java.io.Serializable

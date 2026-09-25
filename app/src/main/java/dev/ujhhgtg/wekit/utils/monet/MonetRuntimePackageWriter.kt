package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ComplexUtil
import com.reandroid.arsc.coder.UnitDimension
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipEntry

/**
 * Writes the runtime resource package that [MonetEngine] hands to
 * `android.content.res.loader.ResourcesProvider.loadFromTable`.
 *
 * This replaces the 09-19 RRO/Magisk pipeline (`MonetOverlayApkWriter` + `MonetModulePackager` +
 * `MonetApkSigner`). Consequences that drove the rewrite:
 *
 *  - No `AndroidManifest.xml` and no `overlay` element: the package is not installed, it is loaded
 *    straight out of WeChat's cache dir by the same process that owns the `AssetManager`.
 *  - No signing: `ResourcesProvider` is not `PackageManager`, so v1/v2 signatures are dead weight.
 *  - The package must be named after the *host* (`com.tencent.mm`), not `monet.*`, otherwise the
 *    provider cannot bind the overriding entries to the base package.
 *  - Publish atomically through a sibling temp file: scoped storage refuses `unlink + create` on an
 *    inode owned by the storage holder, but `rename()` over it works.
 */
object MonetRuntimePackageWriter {

    private const val TAG = "MonetRuntimePackageWriter"

    /**
     * Creates the runtime package at [output]. Existing files are replaced atomically.
     *
     * @param packageName must be the host package (`com.tencent.mm`) for the provider to bind.
     */
    fun write(
        output: File,
        packageName: String,
        plan: MonetOverlayPlan,
    ): Boolean {
        if (plan.isEmpty) {
            // 一个角色都没解析出来时不该抛异常打断整条流程：调用方会把它当成
            // 「本次没有可应用的内容」处理（用户看到的是解析结果，而不是崩溃）。
            WeLogger.w(TAG, "runtime package skipped: empty plan")
            return false
        }
        val tmp = File(
            output.parentFile,
            ".${output.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}",
        )
        try {
            writeTo(tmp, packageName, plan)
            if (!tmp.renameTo(output)) {
                // rename rejected (some FUSE/MediaProvider layers do not permit it); a byte copy is
                // idempotent for our own file, so fall back to it.
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return true
    }

    private fun writeTo(
        output: File,
        packageName: String,
        plan: MonetOverlayPlan,
    ) {
        val apk = ApkModule()
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)

        val specFlags = mutableMapOf<Pair<String, String>, Int>()
        fun record(type: String, name: String, qualifiers: String) {
            val key = type to name
            specFlags[key] = specFlags.getOrDefault(key, 0) or qualifierFlags(qualifiers)
        }

        plan.colors.forEach { color ->
            val name = color.binding.name
            color.light?.let {
                pkg.getOrCreate("", "color", name)!!.setColorValue(it)
                record("color", name, "")
            }
            color.night?.let {
                pkg.getOrCreate("-night", "color", name)!!.setColorValue(it)
                record("color", name, "-night")
            }
        }
        plan.literalColors.forEach { color ->
            val name = color.binding.name
            pkg.getOrCreate("", "color", name)!!
                .setValueAsRaw(ValueType.COLOR_ARGB8, color.lightArgb)
            color.nightArgb?.let {
                pkg.getOrCreate("-night", "color", name)!!
                    .setValueAsRaw(ValueType.COLOR_ARGB8, it)
                record("color", name, "-night")
            }
            record("color", name, "")
        }
        plan.strings.forEach { string ->
            val name = string.binding.name
            pkg.getOrCreate(string.qualifiers, "string", name)!!
                .setValueAsString(string.value)
            record("string", name, string.qualifiers)
        }
        plan.drawables.forEach { drawable ->
            val name = drawable.binding.name
            val type = drawable.binding.type
            pkg.getOrCreate(drawable.lightQualifiers, type, name)
            record(type, name, drawable.lightQualifiers)
            drawable.night?.let {
                pkg.getOrCreate(drawable.nightQualifiers, type, name)
                record(type, name, drawable.nightQualifiers)
            }
        }
        plan.drawables.forEach { drawable ->
            val name = drawable.binding.name
            val type = drawable.binding.type
            addXmlResource(apk, pkg, type, drawable.lightQualifiers, name, drawable.light)
            drawable.night?.let {
                addXmlResource(apk, pkg, type, drawable.nightQualifiers, name, it)
            }
        }

        table.refreshFull()
        specFlags.forEach { (key, flags) -> markSpecFlags(pkg, key.first, key.second, flags) }
        apk.refreshTable()
        freezeCanonicalTable(apk, table)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    private fun com.reandroid.arsc.value.Entry.setColorValue(value: ColorValue) {
        when (value) {
            is ColorValue.Reference -> setValueAsReference(value.id)
            is ColorValue.Literal -> setValueAsRaw(ValueType.COLOR_ARGB8, value.argb)
        }
    }

    private fun markSpecFlags(pkg: PackageBlock, type: String, name: String, flags: Int) {
        val entryId = requireNotNull(pkg.getResource(type, name)).resourceId and 0xffff
        requireNotNull(pkg.getSpecTypePair(type)).specBlock.getSpecFlag(entryId)
            .setInteger(flags)
    }

    private fun qualifierFlags(qualifiers: String): Int {
        if (qualifiers.isEmpty()) return 0
        val parts = qualifiers.removePrefix("-").split('-')
        var result = 0
        if ("night" in parts) result = result or NATIVE_CONFIG_UI_MODE
        if (parts.any { it == "anydpi" || it == "nodpi" || it.endsWith("dpi") }) {
            result = result or NATIVE_CONFIG_DENSITY
        }
        if (parts.any { it.length > 1 && it[0] == 'v' && it.drop(1).all(Char::isDigit) }) {
            result = result or NATIVE_CONFIG_VERSION
        }
        if (parts.firstOrNull()?.matches(Regex("[a-z]{2,3}")) == true) {
            result = result or NATIVE_CONFIG_LOCALE
        }
        return result
    }

    /**
     * ARSCLib 1.4.0 leaves several aapt2 resource-table fields unset when a table is created
     * from scratch. Android's readers are not required to repair those fields. Freeze a canonical
     * byte source after the final refresh so a later BlockInputSource refresh cannot erase them.
     */
    private fun freezeCanonicalTable(apk: ApkModule, table: TableBlock) {
        val bytes = table.bytes
        val tableStrings = table.stringPool
        if (tableStrings.isEmpty) {
            val offset = table.countUpTo(tableStrings)
            putI32(bytes, offset + 20, tableStrings.headerBlock.headerSize)
        }
        table.listPackages().forEach { pkg ->
            val packageOffset = table.countUpTo(pkg)
            putI32(bytes, packageOffset + 0x110, 0)
            putI32(bytes, packageOffset + 0x118, 0)
            pkg.listSpecTypePairs().forEach { pair ->
                val specOffset = table.countUpTo(pair.specBlock)
                putU16(bytes, specOffset + 10, pair.countTypeBlocks())
            }
        }

        apk.removeInputSource(TableBlock.FILE_NAME)
        apk.add(ByteInputSource(bytes, TableBlock.FILE_NAME).apply {
            method = ZipEntry.STORED
            sort = 1
        })
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        putU16(bytes, offset, value)
        putU16(bytes, offset + 2, value ushr 16)
    }

    private fun addXmlResource(
        apk: ApkModule,
        pkg: PackageBlock,
        type: String,
        qualifiers: String,
        name: String,
        node: XmlNode,
    ) {
        val path = "res/$type$qualifiers/$name.xml"
        pkg.getOrCreate(qualifiers, type, name)!!.setValueAsString(path)
        val document = ResXmlDocument().apply { packageBlock = pkg }
        document.newElement(node.name).write(node, pkg)
        document.refreshFull()
        apk.add(BlockInputSource(path, document))
    }

    private fun ResXmlElement.write(node: XmlNode, pkg: PackageBlock) {
        node.attributes.forEach { attribute ->
            createAndroidAttribute(attribute.name, attribute.id).apply {
                when (val value = attribute.value) {
                    is XmlValue.Reference -> {
                        valueType = ValueType.REFERENCE
                        data = value.id
                    }
                    is XmlValue.NamedReference -> {
                        valueType = ValueType.REFERENCE
                        data = requireNotNull(pkg.getResource(value.type, value.name)).resourceId
                    }
                    is XmlValue.Color -> {
                        valueType = ValueType.COLOR_ARGB8
                        data = value.argb
                    }
                    is XmlValue.Dimension -> {
                        valueType = ValueType.DIMENSION
                        data = ComplexUtil.encodeComplex(value.dp, UnitDimension.DP)
                    }
                    is XmlValue.Integer -> {
                        valueType = ValueType.DEC
                        data = value.value
                    }
                    is XmlValue.Boolean -> setValueAsBoolean(value.value)
                    is XmlValue.Float -> {
                        valueType = ValueType.FLOAT
                        data = java.lang.Float.floatToIntBits(value.value)
                    }
                    is XmlValue.String -> setValueAsString(value.value)
                }
            }
        }
        node.children.forEach { child -> newElement(child.name).write(child, pkg) }
    }

    private const val NATIVE_CONFIG_LOCALE = 0x00000004
    private const val NATIVE_CONFIG_DENSITY = 0x00000100
    private const val NATIVE_CONFIG_VERSION = 0x00000400
    private const val NATIVE_CONFIG_UI_MODE = 0x00001000
}

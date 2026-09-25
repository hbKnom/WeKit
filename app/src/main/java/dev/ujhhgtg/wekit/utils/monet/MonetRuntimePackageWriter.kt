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
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipEntry

/**
 * Writes the runtime resource package that [MonetEngine] hands to
 * `android.content.res.loader.ResourcesProvider.loadFromApk`.
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
 *
 * **铁律：覆盖条目必须写在宿主原来的资源 id 上。**
 *
 * `ResourcesProvider` 是**按 id** 合并的：包名（`com.tencent.mm`）对齐只决定「能不能绑上」，
 * 真正决定「替换哪一个资源」的是 `(packageId, typeId, entryId)` 三元组。早期实现用
 * `PackageBlock.getOrCreate(qualifiers, type, name)` 按**名字**建表，ARSCLib 会给这个全新表
 * 自己分配 typeId / entryId（从 1 开始连续分配），于是：
 *
 *  - 颜色写到了宿主 `typeId=1` 的条目上 —— 那通常是 `anim`/别的类型，颜色没落到该改的地方
 *    （用户看到的「莫奈不生效」），
 *  - 更糟的是把宿主的 `anim` 条目覆盖成 `COLOR_RGB8`，微信一取页面切换动画插值器就抛
 *    `Resources$NotFoundException: Resource ID #0x7f010092 type #0x1d is not valid`，
 *    直接闪退（2026-09-25 实机日志 `MonetResourceResolver: resolved 231 roles` 之后必崩）。
 *
 * 所以这里只走 [AlignedEntryWriter]：用 [MonetBinding.id]（解析阶段从宿主资源表读到的真实 id）
 * 拆出 typeId / entryId，在**同一个 typeId 与 entryId** 上建条目，并交叉校验同一个 typeId 不会
 * 对应两个不同的类型名（多 APK 合并时 id 撞车的情况）。名字为合成资源（id==0，例如自适应图标
 * 的三个图层）时按 [syntheticId] 分配宿主未占用的高 entryId。
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
            if (!writeTo(tmp, packageName, plan)) {
                tmp.delete()
                return false
            }
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

    /**
     * 为 WeKit 合成资源（自适应图标的背景/前景/单色图层）借一个宿主不可能占用的槽位。
     *
     * 槽位 = 宿主同类型里最高的 entryId 之后，所以宿主同类型不存在这个 entryId，
     * 覆盖它不可能碰到别人的资源（这是 [AlignedEntryWriter] 拒绝 id==0 之后唯一的合法来源）。
     */
    fun syntheticId(typeId: Int, hostHighestEntryId: Int, sequence: Int): Int {
        val entryId = (hostHighestEntryId + 1 + sequence).coerceIn(1, 0xfffe)
        return (HOST_PACKAGE_ID shl 24) or ((typeId and 0xff) shl 16) or (entryId and 0xffff)
    }

    private fun writeTo(
        output: File,
        packageName: String,
        plan: MonetOverlayPlan,
    ): Boolean {
        val apk = ApkModule()
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)

        val specFlags = mutableMapOf<Pair<String, String>, Int>()
        fun record(type: String, name: String, qualifiers: String) {
            val key = type to name
            specFlags[key] = specFlags.getOrDefault(key, 0) or qualifierFlags(qualifiers)
        }

        val aligned = AlignedEntryWriter(pkg)

        plan.colors.forEach { color ->
            val binding = color.binding
            color.light?.let { value ->
                aligned.entry(binding, "")?.let { entry ->
                    entry.setColorValue(value)
                    record(binding.type, binding.name, "")
                }
            }
            color.night?.let { value ->
                aligned.entry(binding, NIGHT_QUALIFIERS)?.let { entry ->
                    entry.setColorValue(value)
                    record(binding.type, binding.name, NIGHT_QUALIFIERS)
                }
            }
        }
        plan.literalColors.forEach { color ->
            val binding = color.binding
            aligned.entry(binding, "")?.let { entry ->
                entry.setValueAsRaw(ValueType.COLOR_ARGB8, color.lightArgb)
                record(binding.type, binding.name, "")
            }
            color.nightArgb?.let { argb ->
                aligned.entry(binding, NIGHT_QUALIFIERS)?.let { entry ->
                    entry.setValueAsRaw(ValueType.COLOR_ARGB8, argb)
                    record(binding.type, binding.name, NIGHT_QUALIFIERS)
                }
            }
        }
        plan.strings.forEach { string ->
            val binding = string.binding
            aligned.entry(binding, string.qualifiers)?.let { entry ->
                entry.setValueAsString(string.value)
                record(binding.type, binding.name, string.qualifiers)
            }
        }
        // drawable 条目的值必须是「我们刚写进去的那个 XML 的路径」：宿主按 id 取 drawable 时
        // 走的是 value=字符串路径 -> 文件，类型名保持不变，所以既能替换又不改变宿主的取用方式。
        plan.drawables.forEach { drawable ->
            val binding = drawable.binding
            aligned.entry(binding, drawable.lightQualifiers)?.let { entry ->
                val path = xmlPath(binding.type, drawable.lightQualifiers, binding.name)
                entry.setValueAsString(path)
                addXmlResource(apk, pkg, path, drawable.light)
                record(binding.type, binding.name, drawable.lightQualifiers)
            }
            drawable.night?.let { node ->
                aligned.entry(binding, drawable.nightQualifiers)?.let { entry ->
                    val path = xmlPath(binding.type, drawable.nightQualifiers, binding.name)
                    entry.setValueAsString(path)
                    addXmlResource(apk, pkg, path, node)
                    record(binding.type, binding.name, drawable.nightQualifiers)
                }
            }
        }

        table.refreshFull()
        specFlags.forEach { (key, flags) -> markSpecFlags(pkg, key.first, key.second, flags) }
        apk.refreshTable()
        val mismatch = aligned.verify()
        if (mismatch != null) {
            // id 被 ARSCLib 重新分配过 = 覆盖会落到别的资源上（正是闪退的成因），宁可整包不写。
            WeLogger.e(TAG, "runtime package rejected: $mismatch")
            return false
        }
        freezeCanonicalTable(apk, table)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
        WeLogger.i(TAG, "runtime package written: ${aligned.summary()}")
        return true
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
        path: String,
        node: XmlNode,
    ) {
        val document = ResXmlDocument().apply { packageBlock = pkg }
        document.newElement(node.name).write(node, pkg)
        document.refreshFull()
        apk.add(BlockInputSource(path, document))
    }

    private fun xmlPath(type: String, qualifiers: String, name: String) = "res/$type$qualifiers/$name.xml"

    /**
     * 按**宿主 id** 建表的写入口。
     *
     * * `binding.id != 0`：宿主真实资源 id，直接拆 typeId/entryId 写入同一个槽位。
     * * `binding.id == 0`：WeKit 自己合成的资源（自适应图标图层等）。这类条目宿主没有，
     *   但也不能随便挑一个 entryId —— 挑中宿主已有的 entryId 就等于覆盖了别人的资源。
     *   调用方（[MonetAssetInjector]）必须先用 [syntheticId] 从「宿主同类型最高 entryId 之上」
     *   借一个槽位；这里兜底拒绝 id==0，绝不回退到「顺序分配」。
     *
     * 失败的条目一律**跳过并计数**：少替换几个资源只是观感问题，写到错的地方是闪退。
     */
    private class AlignedEntryWriter(private val pkg: PackageBlock) {

        private val typeNames = linkedMapOf<Int, String>()
        private var written = 0
        private val skipped = linkedMapOf<String, Int>()
        private val created = mutableListOf<Pair<Entry, Int>>()

        fun entry(binding: MonetBinding, qualifiers: String): Entry? {
            val id = binding.id
            if (id == 0) return skip(binding, "id==0（合成资源必须先用 syntheticId 借槽位）")
            if ((id ushr 24) and 0xff != HOST_PACKAGE_ID) {
                return skip(binding, "packageId 不是宿主 0x${HOST_PACKAGE_ID.toString(16)}")
            }
            val typeId = (id ushr 16) and 0xff
            val entryId = id and 0xffff
            if (typeId == 0 || entryId == 0) return skip(binding, "id 退化 (0x${id.toUInt().toString(16)})")
            val known = typeNames[typeId]
            if (known != null && !known.equals(binding.type, ignoreCase = true)) {
                // 同一个 typeId 出现在两个类型名下 = 多 APK 合并时 id 撞车，写下去就是乱盖。
                return skip(binding, "typeId $typeId 同时被 $known 与 ${binding.type} 使用")
            }
            typeNames[typeId] = binding.type
            pkg.getOrCreateSpecTypePair(typeId, binding.type)
            val entry = pkg.getOrCreateEntry(typeId.toByte(), entryId.toShort(), qualifiers)
                ?: return skip(binding, "ARSCLib 未能创建条目")
            if (entry.name != binding.name) entry.setName(binding.name)
            written++
            created += entry to id
            return entry
        }

        private fun skip(binding: MonetBinding, reason: String): Entry? {
            val key = reason.substringBefore('（')
            skipped[key] = skipped.getOrDefault(key, 0) + 1
            if (skipped[key] == 1) {
                WeLogger.w(
                    TAG,
                    "skip overlay 0x${binding.id.toUInt().toString(16)} ${binding.type}/${binding.name}: $reason",
                )
            }
            return null
        }

        /** 校验每个条目真的落在它该在的 id 上；返回非 null 表示必须放弃这个包。 */
        fun verify(): String? {
            created.forEach { (entry, expected) ->
                val id = entry.resourceId
                if ((id ushr 24) != HOST_PACKAGE_ID || id == 0) {
                    return "entry ${entry.name} landed on 0x${id.toUInt().toString(16)}"
                }
                if (id != expected) {
                    // 条目被挪了位置 = 覆盖会落到别的资源上（宿主动画被写成颜色就是这么来的），
                    // 这种情况必须整包放弃，绝不放行。
                    return "entry ${entry.name} expected 0x${expected.toUInt().toString(16)} " +
                        "but landed on 0x${id.toUInt().toString(16)}"
                }
                val entryTypeId = (id ushr 16) and 0xff
                val expectedType = typeNames[entryTypeId]
                if (expectedType != null && !entry.name.isNullOrBlank()) {
                    // 名字与 id 都不许被改写：宿主按 id 取值、按名做 overlay 校验，两者都要对齐。
                    val nameEntry = pkg.getResource(expectedType, entry.name)
                    if (nameEntry == null) {
                        return "entry ${entry.name} is not reachable by name in type $expectedType"
                    }
                }
            }
            return null
        }

        fun summary(): String = buildString {
            append("aligned $written entries")
            append(", types ")
            append(typeNames.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            if (skipped.isNotEmpty()) {
                append(", skipped ")
                append(skipped.entries.joinToString { "${it.key}×${it.value}" })
            }
        }
    }

    private const val HOST_PACKAGE_ID = 0x7f
    private const val NIGHT_QUALIFIERS = "-night"

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

package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResFile
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.InflaterInputStream

object MonetApkResourceGraphLoader {

    private const val TAG = "MonetApkResourceGraphLoader"

    fun load(
        apkPaths: List<File>,
        targetPackage: String,
        onProgress: (detail: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): MonetResourceGraph {
        val resources = linkedMapOf<Int, MutableResource>()
        val xmlDocuments = mutableListOf<OwnedXml>()

        apkPaths.forEachIndexed { index, apk ->
            onProgress("打开 ${apk.name}", index, apkPaths.size)
            ApkModule.loadApkFile(apk).apply { setLoadDefaultFramework(false) }.use { module ->
                // ABI/code-only splits have no resources.arsc. Check entry presence rather than
                // catching parser errors: an existing but malformed table must still fail.
                if (!module.hasTableBlock()) {
                    onProgress("跳过 ${apk.name}：不含资源表", index, apkPaths.size)
                    return@use
                }
                val resFiles = module.listResFiles().toList()
                // 全量预计算 fileStructure() 等于把整个 res 目录读一遍（PNG 还要解 IDAT 算统计），
                // 实机日志里单次解析 100 秒以上、启动期主线程卡到 2.6 秒，绝大部分耗在这里，
                // 而且会在解析线程上制造大量垃圾对象引发 GC 风暴。改成按需 + 记忆化：
                // 只有资源值真的引用到的文件才会被读。见 SKILL「莫奈解析：文件结构按需计算」。
                val structures = LazyFileStructures(resFiles)
                val tableStart = System.nanoTime()
                onProgress("解析 ${apk.name} 的资源表", index, apkPaths.size)
                // 后面解析二进制 XML 时要用宿主 PackageBlock 做值解析，所以在这里接出来。
                var hostPackage: PackageBlock? = null
                module.tableBlock.listPackages()
                    .filter { it.name == targetPackage }
                    .forEach { pkg ->
                        hostPackage = pkg
                        pkg.getResources().asSequence().forEach { resource ->
                            resources.merge(resource, apk, structures)
                        }
                    }
                WeLogger.i(
                    TAG,
                    "${apk.name}: ${resources.size} 个资源条目，表遍历 ${(System.nanoTime() - tableStart) / 1_000_000} ms",
                )

                val xmlStart = System.nanoTime()
                val binaryXmlCount = resFiles.count { it.isBinaryXml }
                onProgress("解析 ${apk.name} 的 $binaryXmlCount 个二进制 XML", index, apkPaths.size)
                val candidates = resFiles.mapNotNull { resFile ->
                    val owners = resFile.asSequence()
                        .filter {
                            it.packageBlock.name == targetPackage &&
                                it.typeName in MONET_XML_RESOURCE_TYPES
                        }
                        .map { entry ->
                            XmlIdentity(entry.resourceId, entry.resConfig.qualifiers, resFile.filePath)
                        }
                        .toList()
                    if (owners.isEmpty() || !resFile.isBinaryXml) null else resFile to owners
                }
                val xmlFailures = hostPackage?.let { parseXmlInto(candidates, it, xmlDocuments) } ?: 0
                WeLogger.i(
                    TAG,
                    "${apk.name}: $binaryXmlCount 个二进制 XML（候选 ${candidates.size}），读取 " +
                        "${(System.nanoTime() - xmlStart) / 1_000_000} ms" +
                        "，文件结构按需读了 ${structures.readCount} 个" +
                        if (xmlFailures > 0) "，XML 解析失败 $xmlFailures 个" else "",
                )
            }
            onProgress("完成 ${apk.name}", index + 1, apkPaths.size)
        }

        val definitions = linkedMapOf<XmlIdentity, MonetXmlElement>()
        xmlDocuments.forEach { ownedXml ->
            val definition = ownedXml.xml.root
            val existing = definitions[ownedXml.identity]
            when {
                existing == null -> definitions[ownedXml.identity] = definition
                // 同一个 id 被多个 APK 重复定义且内容不同（厂商 overlay / 重复 split）。
                // 旧实现直接抛错，会让整次「莫奈解析」失败，用户看到的就是解析出问题。
                existing != definition -> WeLogger.w(
                    TAG,
                    "conflicting binary XML for ${ownedXml.identity}, keeping the first definition",
                )
            }
        }
        val xmlByOwner = definitions.entries.groupBy(
            keySelector = { it.key.ownerId },
            valueTransform = Map.Entry<XmlIdentity, MonetXmlElement>::value,
        )
        return MonetResourceGraph(resources.values.map(MutableResource::toNode), xmlByOwner)
    }

    /**
     * 解析二进制 XML：实机冷启动这一步 75 s、热态 11 s（11000+ 个文件），是整条解析链最贵的一步。
     *
     * 按批并行：一批内先顺序读出字节（zip 解压本身便宜），再在固定大小线程池里解析成本地模型。
     * 线程数固定（≤4）、线程为守护线程，配合调用方的后台线程优先级，避免和微信主线程抢 CPU；
     * 峰值内存受批大小约束。单个文件解析失败只丢它自己（返回失败计数），
     * 不再让整个「莫奈解析」因为一个坏 XML 而失败。
     */
    private fun parseXmlInto(
        candidates: List<Pair<ResFile, List<XmlIdentity>>>,
        hostPackage: PackageBlock,
        out: MutableList<OwnedXml>,
    ): Int {
        if (candidates.isEmpty()) return 0
        var failures = 0
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "wekit-monet-xml").apply { isDaemon = true }
        }
        try {
            candidates.chunked(XML_BATCH_SIZE).forEach { batch ->
                val payloads = batch.map { (file, _) ->
                    runCatching { file.inputSource.openStream().use { it.readBytes() } }.getOrNull()
                }
                val tasks = batch.indices.map { i ->
                    val bytes = payloads[i]
                    pool.submit(
                        Callable {
                            if (bytes == null) return@Callable null
                            runCatching {
                                // apply 的接收者是 ResXmlDocument，这里的 packageBlock 是它的
                                // 属性（ARSCLib 的 setPackageBlock）；右边的 hostPackage 才是参数。
                                val document = ResXmlDocument().apply { packageBlock = hostPackage }
                                document.readBytes(ByteArrayInputStream(bytes))
                                MonetBinaryXmlReader.read(document)
                            }.getOrElse { t ->
                                failures++
                                if (failures <= 5) {
                                    WeLogger.w(TAG, "二进制 XML 解析失败，已跳过：${t.message}")
                                }
                                null
                            }
                        },
                    )
                }
                tasks.forEachIndexed { i, task ->
                    val xml = runCatching { task.get() }.getOrNull() ?: return@forEachIndexed
                    batch[i].second.forEach { identity -> out += OwnedXml(identity, xml) }
                }
            }
        } finally {
            pool.shutdown()
        }
        return failures
    }

    private const val XML_BATCH_SIZE = 192

    private fun MutableMap<Int, MutableResource>.merge(
        resource: ResourceEntry,
        apk: File,
        structures: LazyFileStructures,
    ) {
        if (resource.isEmpty) return
        val id = resource.resourceId
        val type = resource.type ?: return
        val name = resource.name ?: return
        val key = MonetResourceKey(type = type, name = name)
        val merged = getOrPut(id) { MutableResource(id, key) }
        if (merged.key != key) {
            // 同一 id 在不同 APK 里换了身份：以先到者为准，保留旧身份继续合并值。
            WeLogger.w(
                TAG,
                "resource 0x${id.toUInt().toString(16)} changes identity from ${merged.key} to $key in $apk",
            )
        }
        resource.asSequence().forEach { entry ->
            val qualifiers = entry.resConfig.qualifiers
            val value = if (entry.isComplex) {
                val complex = entry.resTableMapEntry ?: return@forEach
                MonetResourceValue.Complex(
                    parentId = complex.parentId,
                    items = complex.iterator().asSequence().mapNotNull { item ->
                        item.toMonetValue(structures)?.let { MonetComplexValue(item.nameId, it) }
                    }.toList(),
                )
            } else {
                entry.resValue?.toMonetValue(structures) ?: return@forEach
            }
            val existing = merged.valuesByQualifiers[qualifiers]
            when {
                existing == null -> merged.valuesByQualifiers[qualifiers] = value
                // 同一个 id + qualifiers 在不同 APK 里给了不同的值：保留先到的，
                // 解析继续（判定规则只看「有没有这个值」，不看它被定义了两次）。
                existing != value -> WeLogger.w(
                    TAG,
                    "conflicting values for 0x${id.toUInt().toString(16)} ($key) qualifiers '$qualifiers' in $apk",
                )
            }
        }
    }

    /** 返回 null 表示这条 ARSC 值无法解读：调用方跳过它，而不是让整次解析中断。 */
    private fun ValueItem.toMonetValue(structures: LazyFileStructures): MonetResourceValue? {
        val valueType = valueType ?: return null
        if (valueType.isReference) return MonetResourceValue.Reference(data, valueType.name)
        if (valueType == ValueType.STRING) {
            val stringValue = valueAsString
            if (stringValue != null && (structures.contains(stringValue) || stringValue.startsWith("res/"))) {
                return MonetResourceValue.File(stringValue, structures[stringValue])
            }
            if (stringValue != null) return MonetResourceValue.Text(stringValue)
        }
        return MonetResourceValue.Literal(
            valueType = valueType.name,
            data = Integer.toUnsignedLong(data),
        )
    }

    /**
     * 按需、记忆化的 `res` 文件结构查询。
     *
     * [com.reandroid.apk.ResFile.fileStructure] 对 PNG 要读 4096 字节文件头、必要时还要解 IDAT 算
     * 像素统计；旧实现用 `resFiles.associate { it.filePath to it.fileStructure() }` 对所有文件预计算
     * （`associate` 是急切的），一次解析因此要多读上千个文件、并在解析线程上产生大量临时对象。
     * 解析阶段真正需要的只是「值引用到的那些文件」的结构，所以这里延后到第一次访问。
     */
    private class LazyFileStructures(resFiles: List<com.reandroid.apk.ResFile>) {

        private val byPath = resFiles.associateBy { it.filePath }
        private val cache = HashMap<String, MonetFileStructure?>()

        var readCount = 0
            private set

        fun contains(path: String): Boolean = byPath.containsKey(path)

        operator fun get(path: String): MonetFileStructure? {
            if (cache.containsKey(path)) return cache[path]
            readCount++
            val structure = byPath[path]?.fileStructure()
            cache[path] = structure
            return structure
        }
    }

    private fun com.reandroid.apk.ResFile.fileStructure(): MonetFileStructure {
        val extension = inputSource.extension.uppercase()
        if (!extension.endsWith("PNG")) return MonetFileStructure(extension)
        val format = if (extension.contains(".9.")) "9PNG" else "PNG"
        val header = inputSource.getBytes(4096)
        if (header.size < 26 || header[0].toInt() and 0xff != 0x89 || String(header, 1, 3) != "PNG") {
            return MonetFileStructure(format)
        }
        fun intAt(offset: Int): Int = header[offset].toInt() and 0xff shl 24 or
            (header[offset + 1].toInt() and 0xff shl 16) or
            (header[offset + 2].toInt() and 0xff shl 8) or
            (header[offset + 3].toInt() and 0xff)
        var offset = 8
        var firstDataLength: Int? = null
        var ninePatchLength: Int? = null
        val compressed = ByteArrayOutputStream()
        while (offset + 12 <= header.size) {
            val length = intAt(offset)
            if (length < 0) break
            val type = String(header, offset + 4, 4)
            if (type == "IDAT" && firstDataLength == null) firstDataLength = length
            if (type == "npTc") ninePatchLength = length
            if (offset + 12L + length > header.size) break
            if (type == "IDAT") compressed.write(header, offset + 8, length)
            offset += length + 12
            if (type == "IEND") break
        }
        val pixels = if (intAt(16).toLong() * intAt(20) <= 8192 && header[24].toInt() == 8) {
            pixelStatistics(intAt(16), intAt(20), header[25].toInt() and 0xff, compressed.toByteArray())
        } else null
        return MonetFileStructure(
            format,
            intAt(16),
            intAt(20),
            header[25].toInt() and 0xff,
            firstDataLength,
            ninePatchLength,
            pixels?.sampleSum,
            pixels?.alphaSum,
            pixels?.distinctSamples,
            pixels?.sha256,
        )
    }

    private fun pixelStatistics(
        width: Int,
        height: Int,
        colorType: Int,
        compressed: ByteArray,
    ): PixelStatistics? {
        val bytesPerPixel = when (colorType) {
            0 -> 1
            4 -> 2
            else -> return null
        }
        val stride = width * bytesPerPixel
        val inflated = runCatching {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (inflated.size < (stride + 1) * height) return null
        val previous = ByteArray(stride)
        val current = ByteArray(stride)
        val samples = hashSetOf<Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        var sampleSum = 0L
        var alphaSum = 0L
        var source = 0
        repeat(height) {
            val filter = inflated[source++].toInt() and 0xff
            for (column in 0 until stride) {
                val raw = inflated[source++].toInt() and 0xff
                val left = if (column >= bytesPerPixel) current[column - bytesPerPixel].toInt() and 0xff else 0
                val up = previous[column].toInt() and 0xff
                val upperLeft = if (column >= bytesPerPixel) previous[column - bytesPerPixel].toInt() and 0xff else 0
                current[column] = (raw + when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upperLeft)
                    else -> return null
                }).toByte()
            }
            for (pixel in 0 until width) {
                val sample = current[pixel * bytesPerPixel].toInt() and 0xff
                val alpha = if (bytesPerPixel == 2) current[pixel * bytesPerPixel + 1].toInt() and 0xff else 255
                sampleSum += sample
                alphaSum += alpha
                samples += sample shl 8 or alpha
            }
            digest.update(current)
            current.copyInto(previous)
        }
        return PixelStatistics(
            sampleSum,
            alphaSum,
            samples.size,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val upDistance = kotlin.math.abs(prediction - up)
        val upperLeftDistance = kotlin.math.abs(prediction - upperLeft)
        return if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) left
        else if (upDistance <= upperLeftDistance) up else upperLeft
    }

    private data class PixelStatistics(
        val sampleSum: Long,
        val alphaSum: Long,
        val distinctSamples: Int,
        val sha256: String,
    )

    private data class MutableResource(
        val id: Int,
        val key: MonetResourceKey,
        val valuesByQualifiers: MutableMap<String, MonetResourceValue> = linkedMapOf(),
    ) {
        fun toNode() = MonetResourceNode(
            id = id,
            key = key,
            values = valuesByQualifiers.toSortedMap().map { (qualifiers, value) ->
                MonetConfiguredValue(qualifiers, value)
            },
        )
    }

    private data class XmlIdentity(
        val ownerId: Int,
        val qualifiers: String,
        val path: String,
    )

    private data class OwnedXml(
        val identity: XmlIdentity,
        val xml: MonetBinaryXml,
    )

    private val MONET_XML_RESOURCE_TYPES = setOf("color", "drawable", "layout")
}

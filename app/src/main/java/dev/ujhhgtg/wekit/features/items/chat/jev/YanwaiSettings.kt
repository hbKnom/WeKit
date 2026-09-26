package dev.ujhhgtg.wekit.features.items.chat.jev

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Tune
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.ChatInsights
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiProfiles
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiSettings
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevProvider
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.MoodMessageChannel
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.ConversationPickerSection
import dev.ujhhgtg.wekit.ui.utils.rememberAllConversations
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 潜语（合并后的聊天分析决策）设置页。
 *
 * 上游 wechatmood 是独立 APK，设置页要同时承担「配置」和「跨进程下发配置给微信」两件事，
 * 所以有状态概览、连接检测、日志导出、使用引导等一整套。本模块设置与 hook 同进程，
 * 保存即生效。
 *
 * 合并后的设置页分四段，顺序就是用户排查问题的顺序：
 *  1. 开关（分析 / 气泡卡 / 回插会话 / 也分析我发的 / 诊断）
 *  2. 分析范围（全部聊天 / 仅选定聊天 + 会话选择器）
 *  3. 上下文条数（0–20）
 *  4. 渠道与密钥（沿用上游 `channel_{id}_{key,endpoint,model}` 键位，便于迁移）
 * 末尾是运行状态（已发请求数、成功/失败）、四个动作按钮和「最近解读」流水 ——
 * 用户反馈「有些消息能出结果、有些不行」时，这一段能直接看出是额度/限流还是配置问题。
 *
 * 控件约定（WeKit 侧）：
 *  - 对话框正文必须走 [AlertDialogContent] 的 `text = { … }` 槽位（该函数的尾参是间距 Dp，
 *    不能用尾随 lambda）；
 *  - 开关用 [SwitchWidget]（需要 `title`），单选列表用 [RadioButtonWidget]（单选语义 + 无障碍 role）。
 */
object YanwaiSettings {

    fun show(context: Context) {
        showComposeDialog(context) {
            var enabled by remember { mutableStateOf(ModulePrefs.enabled) }
            var showBadge by remember { mutableStateOf(ModulePrefs.showBadge) }
            var displayMessage by remember { mutableStateOf(ModulePrefs.displayMessage) }
            var analyzeSelf by remember { mutableStateOf(ModulePrefs.analyzeSelf) }
            var cardExpanded by remember { mutableStateOf(ModulePrefs.cardExpanded) }
            var showTrend by remember { mutableStateOf(ModulePrefs.showTrend) }
            // 卡片扩展块（建议强度 / 互动均衡 / 话题 / 情绪趋势）：默认全开，可逐项关掉
            var showLevel by remember { mutableStateOf(ModulePrefs.showLevel) }
            var showBalance by remember { mutableStateOf(ModulePrefs.showBalance) }
            var showTopics by remember { mutableStateOf(ModulePrefs.showTopics) }
            var showTrendPanel by remember { mutableStateOf(ModulePrefs.showTrendPanel) }
            var insertFreshText by remember {
                mutableStateOf(ModulePrefs.insertFreshSeconds.toString())
            }
            var explore by remember { mutableStateOf(ModulePrefs.exploreMode) }
            var scopeAll by remember { mutableStateOf(ModulePrefs.scopeAll) }
            var contextLimitText by remember { mutableStateOf(ModulePrefs.contextLimit.toString()) }
            var selectedTalkers by remember { mutableStateOf(ModulePrefs.scopeTalkers) }
            var notice by remember { mutableStateOf("") }
            var refreshKey by remember { mutableStateOf(0) }
            var recent by remember { mutableStateOf(MoodStore.recent()) }
            var runtime by remember { mutableStateOf(runtimeLine()) }

            // 会话标题表：选择器需要它才能把 wxId 存成「能看懂的名字」（存名字是为了在
            // 会话改名/无法查库时仍能显示）。加载走 IO 线程，与选择器共用同一份数据。
            val (options, _) = rememberAllConversations(refreshKey)
            val names = remember(options) { options.associate { it.wxId to it.title } }

            val initialProvider = JevProvider.resolve(
                WePrefs.getStringOrDef(ModulePrefs.KEY_API_PROVIDER, ""),
                WePrefs.getStringOrDef(ModulePrefs.KEY_API_BASE, ""),
            )
            var provider by remember { mutableStateOf(initialProvider) }
            var endpoint by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef("channel_${initialProvider.id}_endpoint", "").ifBlank {
                        if (initialProvider == JevProvider.CUSTOM) "" else initialProvider.endpoint
                    },
                )
            }
            var model by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef("channel_${initialProvider.id}_model", "").ifBlank {
                        initialProvider.model
                    },
                )
            }
            var apiKey by remember {
                mutableStateOf(WePrefs.getStringOrDef("channel_${initialProvider.id}_key", ""))
            }

            fun switchProvider(next: JevProvider) {
                provider = next
                endpoint = WePrefs.getStringOrDef("channel_${next.id}_endpoint", "").ifBlank {
                    if (next == JevProvider.CUSTOM) "" else next.endpoint
                }
                model = WePrefs.getStringOrDef("channel_${next.id}_model", "").ifBlank { next.model }
                apiKey = WePrefs.getStringOrDef("channel_${next.id}_key", "")
            }

            /** 把表单里的值写进 WePrefs；「检测连接」也先走它，保证测的是**保存后**的配置。 */
            fun persist(): Boolean {
                val limit = contextLimitText.trim().toIntOrNull()?.coerceIn(0, ModulePrefs.MAX_CONTEXT_LIMIT)
                if (limit == null) {
                    notice = "上下文条数请填 0–${ModulePrefs.MAX_CONTEXT_LIMIT} 之间的数字"
                    return false
                }
                val fresh = insertFreshText.trim().toIntOrNull()
                    ?.coerceIn(ModulePrefs.MIN_INSERT_FRESH_SECONDS, ModulePrefs.MAX_INSERT_FRESH_SECONDS)
                if (fresh == null) {
                    notice = "回插新鲜度请填 ${ModulePrefs.MIN_INSERT_FRESH_SECONDS}–" +
                        "${ModulePrefs.MAX_INSERT_FRESH_SECONDS} 之间的秒数"
                    return false
                }
                val settings = ApiSettings.fromInput(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    providerId = provider.id,
                    model = model,
                )
                // `read` 必须能返回 null：ApiProfiles 用它判断「这个渠道还没有独立键」，
                // 从而把 1.0.x 的全局配置迁移过去。传 getStringOrDef("") 会让判断永远为假。
                ApiProfiles.valuesToSave(settings) { WePrefs.getString(it) }
                    .forEach { (k, v) -> WePrefs.putString(k, v) }
                ModulePrefs.setSwitch(ModulePrefs.KEY_ENABLED, enabled)
                ModulePrefs.setSwitch(ModulePrefs.KEY_SHOW_BADGE, showBadge)
                ModulePrefs.setDisplayMessage(displayMessage)
                ModulePrefs.setAnalyzeSelf(analyzeSelf)
                ModulePrefs.setCardExpanded(cardExpanded)
                ModulePrefs.setShowTrend(showTrend)
                ModulePrefs.setShowLevel(showLevel)
                ModulePrefs.setShowBalance(showBalance)
                ModulePrefs.setShowTopics(showTopics)
                ModulePrefs.setShowTrendPanel(showTrendPanel)
                ModulePrefs.setInsertFreshSeconds(fresh)
                ModulePrefs.setContextLimit(limit)
                ModulePrefs.setScope(
                    all = scopeAll,
                    talkers = if (scopeAll) emptySet() else selectedTalkers,
                    names = if (scopeAll) emptyMap() else selectedTalkers.associateWith { names[it] ?: it },
                )
                WePrefs.putBool(ModulePrefs.KEY_EXPLORE, explore)
                // WePrefs 的 SQLite 实现每次 put 即落库（save() 只是 commit 的空实现），
                // 这里不需要再调 save()。
                MoodLog.i("设置已保存：provider=${settings.provider.id} model=${settings.model}")
                return true
            }

            fun refreshRuntime() {
                recent = MoodStore.recent()
                runtime = runtimeLine()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_yanwai_name)) },
                confirmButton = {
                    Button(onClick = {
                        runCatching {
                            if (persist()) {
                                ModulePrefs.reload()
                                YanwaiScanner.refresh()
                            }
                        }.onFailure {
                            MoodLog.e("保存失败", it)
                            notice = "保存失败：${it.message}"
                        }
                        if (notice.isEmpty()) onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.yanwai_enable),
                                description = stringResource(R.string.yanwai_enable_desc),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.yanwai_show_badge),
                                description = stringResource(R.string.yanwai_show_badge_desc),
                                checked = showBadge,
                                onCheckedChange = { showBadge = it },
                                trailingDivider = true,
                            )
                        }
                        // 原来独立成「Jev 聊天决策实时分析」那个功能，现在只是本功能的一个展示通道
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.yanwai_display_message),
                                description = stringResource(R.string.yanwai_display_message_desc),
                                checked = displayMessage,
                                onCheckedChange = { displayMessage = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.yanwai_analyze_self),
                                description = stringResource(R.string.yanwai_analyze_self_desc),
                                checked = analyzeSelf,
                                onCheckedChange = { analyzeSelf = it },
                                trailingDivider = true,
                            )
                        }
                        // 卡片外观：默认展开完整解读 + 显示与前几句对比
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.yanwai_card_expanded),
                                description = stringResource(R.string.yanwai_card_expanded_desc),
                                checked = cardExpanded,
                                onCheckedChange = { cardExpanded = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.yanwai_show_trend),
                                description = stringResource(R.string.yanwai_show_trend_desc),
                                checked = showTrend,
                                onCheckedChange = { showTrend = it },
                                trailingDivider = true,
                            )
                        }
                        // ------------------------------------------------------ 卡片扩展
                        item {
                            Text(
                                text = stringResource(R.string.jev_settings_section_ext),
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.jev_ext_level_enable),
                                description = stringResource(R.string.jev_ext_level_enable_desc),
                                checked = showLevel,
                                onCheckedChange = { showLevel = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.jev_ext_balance_enable),
                                description = stringResource(R.string.jev_ext_balance_enable_desc),
                                checked = showBalance,
                                onCheckedChange = { showBalance = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.jev_ext_topics_enable),
                                description = stringResource(
                                    R.string.jev_ext_topics_enable_desc,
                                    ChatInsights.MAX_TOPICS,
                                ),
                                checked = showTopics,
                                onCheckedChange = { showTopics = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.jev_ext_trend_enable),
                                description = stringResource(R.string.jev_ext_trend_enable_desc),
                                checked = showTrendPanel,
                                onCheckedChange = { showTrendPanel = it },
                                trailingDivider = true,
                            )
                        }

                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.yanwai_explore_mode),
                                description = stringResource(R.string.yanwai_explore_mode_desc),
                                checked = explore,
                                onCheckedChange = { explore = it },
                                trailingDivider = true,
                            )
                        }

                        // ---------------------------------------------------------- 分析范围
                        item {
                            Text(
                                text = stringResource(R.string.yanwai_scope),
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        item {
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.yanwai_scope_all),
                                description = stringResource(R.string.yanwai_scope_all_desc),
                                selected = scopeAll,
                                onClick = { scopeAll = true },
                                trailingDivider = true,
                            )
                        }
                        item {
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.yanwai_scope_pick),
                                description = if (scopeAll) {
                                    stringResource(R.string.yanwai_scope_pick_desc)
                                } else {
                                    stringResource(R.string.yanwai_scope_summary, selectedTalkers.size)
                                },
                                selected = !scopeAll,
                                onClick = { scopeAll = false },
                                trailingDivider = true,
                            )
                        }
                        if (!scopeAll) {
                            item {
                                ConversationPickerSection(
                                    selected = selectedTalkers,
                                    onToggle = { wxId, on ->
                                        selectedTalkers = if (on) {
                                            selectedTalkers + wxId
                                        } else {
                                            selectedTalkers - wxId
                                        }
                                        // 名字表只用于「已选 N 个」的可读性，取不到就退回 wxId
                                        names[wxId]?.let { MoodLog.i("潜语范围已选：$it") }
                                    },
                                    refreshKey = refreshKey,
                                )
                            }
                        }

                        // ---------------------------------------------------------- 上下文
                        if (displayMessage) {
                            // 只在真的开着「回插会话」时才需要它：窗口越小越安静
                            item {
                                OutlinedTextField(
                                    value = insertFreshText,
                                    onValueChange = { insertFreshText = it.filter(Char::isDigit).take(4) },
                                    label = { Text(stringResource(R.string.yanwai_insert_fresh)) },
                                    supportingText = { Text(stringResource(R.string.yanwai_insert_fresh_desc)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = contextLimitText,
                                onValueChange = { contextLimitText = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.yanwai_context_limit)) },
                                supportingText = { Text(stringResource(R.string.yanwai_context_limit_desc)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ---------------------------------------------------------- 渠道
                        item {
                            Text(
                                text = stringResource(R.string.yanwai_provider),
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(JevProvider.entries.toList()) { candidate ->
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = candidate.label,
                                description = if (candidate == JevProvider.CUSTOM) {
                                    stringResource(R.string.yanwai_provider_custom_desc)
                                } else {
                                    candidate.endpoint
                                },
                                selected = provider == candidate,
                                onClick = { switchProvider(candidate) },
                                trailingDivider = true,
                            )
                        }

                        if (provider == JevProvider.CUSTOM) {
                            item {
                                OutlinedTextField(
                                    value = endpoint,
                                    onValueChange = { endpoint = it },
                                    label = { Text(stringResource(R.string.yanwai_endpoint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text(stringResource(R.string.yanwai_model)) },
                                singleLine = true,
                                enabled = provider == JevProvider.CUSTOM,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.yanwai_api_key)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ---------------------------------------------------------- 运行状态与动作
                        item {
                            Text(
                                text = runtime,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            )
                        }
                        if (notice.isNotEmpty()) {
                            item {
                                Text(
                                    text = notice,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    runCatching { if (persist()) ModulePrefs.reload() }
                                        .onFailure { notice = "保存失败：${it.message}" }
                                    notice = "正在检测，请稍候…"
                                    SignalAnalyzer.testConnection { ok, message ->
                                        notice = message
                                        refreshRuntime()
                                        if (ok) MoodLog.i("潜语连接检测通过")
                                    }
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_test_connection))
                            }
                        }
                        item {
                            // 立即对「当前屏幕上可见的消息」重跑一遍（不必等下一次滚动/新消息）。
                            Button(
                                onClick = {
                                    YanwaiScanner.reanalyzeVisible()
                                    refreshKey++
                                    refreshRuntime()
                                    notice = "已重新提交本屏可见消息"
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_analyse_now))
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    val cleared = SignalAnalyzer.retryAllFailures()
                                    YanwaiScanner.reanalyseFailed()
                                    refreshRuntime()
                                    notice = if (cleared == 0) {
                                        "没有失败的记录"
                                    } else {
                                        "已清掉 $cleared 条失败记录并重新提交"
                                    }
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_retry_failed))
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    // 必须先数再清：清完再读 size() 永远是 0（此前这里显示「已清 0 条」）
                                    val before = MoodStore.size()
                                    SignalAnalyzer.clearResults()
                                    refreshRuntime()
                                    notice = "已清空结果缓存（$before 条），正在重新分析本屏"
                                    YanwaiScanner.reanalyzeVisible()
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_clear_results))
                            }
                        }

                        // ---------------------------------------------------------- 最近解读
                        item {
                            Button(
                                onClick = {
                                    val entries = MoodStore.recent()
                                    if (entries.isEmpty()) {
                                        notice = context.getString(R.string.yanwai_export_empty)
                                    } else {
                                        copyToClipboard(context, "潜语解读", exportText(entries))
                                        notice = context.getString(R.string.yanwai_export_done, entries.size)
                                    }
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_export))
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.yanwai_recent),
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        if (recent.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.yanwai_recent_empty),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        } else {
                            items(recent) { entry ->
                                val name = names[entry.talker] ?: entry.talker.takeLast(10)
                                Text(
                                    text = "${stamp(entry.at)} · $name · " +
                                        if (entry.ok) entry.label else "分析失败：${entry.note}",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                )
                            }
                        }

                        item {
                            Text(
                                text = stringResource(R.string.yanwai_privacy),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                },
            )
        }
    }

    private fun runtimeLine(): String {
        val (ok, bad) = MoodStore.stats()
        return "本次运行：已发出请求 ${SignalAnalyzer.requestCount} 次 · 成功 $ok · 失败 $bad · " +
            "缓存 ${MoodStore.size()} 条 · 等待中 ${SignalAnalyzer.queuedDepth + MoodStore.pendingCount()} 条"
    }

    /**
     * 导出文本：优先导出**完整解读**（与气泡卡/回插通道同一份结构化结论），
     * 结果缓存已被清掉的历史流水退回它记下的那一行结论/失败原因。
     */
    private fun exportText(entries: List<MoodStore.Entry>): String = buildString {
        append("潜语 · 最近解读（${entries.size} 条）")
        entries.forEach { entry ->
            append('\n')
            append('\n')
            append(stamp(entry.at)).append(" · ").append(entry.talker.takeLast(10))
            append('\n')
            val mood = MoodStore.get(entry.key)
            append(
                when {
                    mood != null -> MoodMessageChannel.format(mood)
                    entry.ok -> entry.label
                    else -> "分析失败：${entry.note}"
                },
            )
        }
    }

    private fun stamp(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}

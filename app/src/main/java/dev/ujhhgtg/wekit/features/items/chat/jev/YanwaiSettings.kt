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
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiProfiles
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiSettings
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevProvider
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

/**
 * 言外设置页。
 *
 * 上游 wechatmood 是独立 APK，设置页要同时承担「配置」和「跨进程下发配置给微信」两件事，
 * 所以有状态概览、连接检测、日志导出、使用引导等一整套。本模块设置与 hook 同进程，
 * 保存即生效，这里只保留真正影响行为的项：
 *  - 三个开关（是否发起分析 / 是否绘制分析卡 / 诊断模式）
 *  - 渠道选择（四个预设或自定义，切换后地址与模型自动匹配，与上游 [JevProvider] 一致）
 *  - Key（按渠道分开保存，切换渠道不会把上一个渠道的 Key 带过来）
 *
 * 键位沿用上游 `channel_{id}_{key,endpoint,model}`，便于直接迁移已有配置。
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
            var explore by remember { mutableStateOf(ModulePrefs.exploreMode) }

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

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_yanwai_name)) },
                confirmButton = {
                    Button(onClick = {
                        runCatching {
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
                            WePrefs.putBool(ModulePrefs.KEY_EXPLORE, explore)
                            // WePrefs 的 SQLite 实现每次 put 即落库（save() 只是 commit 的空实现），
                            // 这里不需要再调 save()。
                            MoodLog.i("设置已保存：provider=${settings.provider.id} model=${settings.model}")
                            YanwaiScanner.refresh()
                        }.onFailure {
                            MoodLog.e("保存失败", it)
                        }
                        onDismiss()
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
                        item {
                            // 立即对「当前屏幕上可见的对方消息」跑一遍分析（不必等下一次滚动/新消息）。
                            Button(
                                onClick = { YanwaiScanner.refresh() },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            ) {
                                Text(stringResource(R.string.yanwai_analyse_now))
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
}

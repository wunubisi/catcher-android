package com.catcher.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.BuildConfig
import com.catcher.app.R
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.ui.HomeViewModel
import com.catcher.app.ui.UiState
import com.catcher.app.ui.components.PlatformDot
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.DangerRed
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.SuccessGreen

/** 设置：显示口径、刷新配置、平台登录入口、关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    var aboutOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardWhite,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "勾选需要抓取的平台；首页只显示勾选的平台。",
                fontSize = 11.sp,
                color = InkTertiary,
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("抓取的平台")
                Spacer(Modifier.weight(1f))
                Text(
                    "全选",
                    fontSize = 11.sp,
                    color = BrandBlue,
                    modifier = Modifier
                        .clickable { vm.setAllEnabled(true) }
                        .padding(4.dp),
                )
                Text(
                    "全不选",
                    fontSize = 11.sp,
                    color = InkTertiary,
                    modifier = Modifier
                        .clickable { vm.setAllEnabled(false) }
                        .padding(4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            state.platforms.forEach { ui ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformDot(ui.platform, 9)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ui.platform.displayName,
                                fontSize = 13.5.sp,
                                color = Ink,
                                fontWeight = FontWeight.Medium,
                            )
                            if (ui.platform.experimental) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFFFFF3E0))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text("实验性", fontSize = 8.sp, color = Color(0xFFB26A00))
                                }
                            }
                        }
                        Text(
                            loginLabel(ui.loginState, ui.displayName),
                            fontSize = 10.5.sp,
                            color = InkTertiary,
                        )
                    }
                    Text(
                        "登录",
                        fontSize = 12.sp,
                        color = BrandBlue,
                        modifier = Modifier
                            .clickable {
                                vm.closeSettings()
                                vm.openLogin(ui.platform)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Switch(
                        checked = ui.enabled,
                        onCheckedChange = { vm.togglePlatformEnabled(ui.platform) },
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandBlue),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { aboutOpen = true }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_exclaim_circle),
                    contentDescription = null,
                    tint = InkTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "关于",
                    fontSize = 14.sp,
                    color = Ink,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text("›", fontSize = 18.sp, color = InkTertiary)
            }

            if (aboutOpen) {
                AlertDialog(
                    onDismissRequest = { aboutOpen = false },
                    title = { Text("Catcher ${BuildConfig.VERSION_NAME}") },
                    text = {
                        Column {
                            Text(
                                "把雨课堂、学习通、学在重邮、批改网的作业汇总到一个列表：统一的课程、状态、" +
                                    "截止时间与时间来源，支持按平台筛选、搜索和手动标记。\n" +
                                    "数据只保存在本机：不监听本地端口，不经过任何中转服务器，所有请求都从这台手机" +
                                    "直接发往各平台；登录态用 Android Keystore 加密后存进本地 SQLite。",
                                fontSize = 12.sp,
                                color = InkSecondary,
                                lineHeight = 19.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "本项目是非官方个人学习项目，与雨课堂、学习通、学在重邮、批改网及其运营方无任何关联；" +
                                    "接口依据公开页面抓包整理，仅用于学习与技术研究。使用时请遵守各平台的用户协议与所在地法律法规。",
                                fontSize = 11.sp,
                                color = InkTertiary,
                                lineHeight = 17.sp,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { aboutOpen = false }) { Text("知道了") }
                    },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = InkSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

private fun loginLabel(state: LoginState, name: String): String = when (state) {
    LoginState.CONNECTED -> "已登录" + if (name.isNotEmpty()) " · $name" else ""
    LoginState.DISCONNECTED -> "未登录"
    LoginState.NEEDS_LOGIN -> "登录态失效，需要重新登录"
    LoginState.ERROR -> "登录异常，建议重新登录"
}

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.domain.model.Account
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.ui.LoginUi
import com.catcher.app.ui.components.PlatformDot
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.DangerRed
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.SuccessGreen

/**
 * 登录面板。
 *
 * 移动端的登录方式与电脑端不同：电脑端起本地窗口显示二维码，手机上没法扫自己的屏幕，
 * 所以二维码平台改成「内嵌网页登录后取 cookie」；学习通仍然走手机号 + 密码接口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    login: LoginUi,
    account: Account?,
    onDismiss: () -> Unit,
    onPassword: (String, String) -> Unit,
    onCookies: (String) -> Unit,
    onSession: (String) -> Unit,
    onWebLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val platform = login.platform
    val loggedIn = account?.loginState == LoginState.CONNECTED
    val maskedName = account?.displayName.orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardWhite,
    ) {
        key(platform) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                    ) { PlatformDot(platform, 10) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${platform.displayName} 登录",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    if (platform.experimental) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFFF3E0))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("实验性", fontSize = 9.sp, color = Color(0xFFB26A00))
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    loginHint(platform),
                    fontSize = 11.5.sp,
                    color = InkTertiary,
                    lineHeight = 17.sp,
                )

                // 已登录：只显示登录态与退出按钮，不再出现登录方式区块；退出后区块才会回来
                if (loggedIn) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEAF8F1))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已登录",
                            fontSize = 12.sp,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (maskedName.isNotEmpty()) maskedName else "登录态有效",
                            fontSize = 11.sp,
                            color = SuccessGreen.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onLogout,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("退出登录并清除本机会话", fontSize = 13.sp, color = DangerRed)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "要换账号，请先退出登录，再用手机密码或网页登录重新授权。",
                        fontSize = 10.5.sp,
                        color = InkTertiary,
                    )
                } else {
                    if (login.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            login.error,
                            fontSize = 12.sp,
                            color = DangerRed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFDEDED))
                                .padding(10.dp),
                        )
                    }

                    val methods = loginMethods(platform)
                    if (Platform.CHAOXING in methods) {
                        Spacer(Modifier.height(18.dp))
                        PasswordForm(login.busy, onPassword)
                        Spacer(Modifier.height(8.dp))
                        // 密码被验证码/风控拦住时的备用通道：一行蓝色小字直接跳网页登录
                        WebLoginHint(login.busy, onWebLogin)
                        Spacer(Modifier.height(14.dp))
                        CookieForm(login.busy, onCookies)
                    }

                    if (Platform.PIGAI in methods || Platform.YUKETANG in methods) {
                        Spacer(Modifier.height(18.dp))
                        WebLoginSection(login.busy, onWebLogin)
                        Spacer(Modifier.height(14.dp))
                        CookieForm(login.busy, onCookies)
                    }

                    if (Platform.TRONCLASS in methods) {
                        Spacer(Modifier.height(18.dp))
                        SessionForm(login.busy, onSession)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onWebLogin,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("去校园网页面登录后取 session", fontSize = 13.sp)
                        }
                    }
                }

                if (login.busy) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = BrandBlue,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在验证登录态…", fontSize = 12.sp, color = InkSecondary)
                    }
                }

                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun PasswordForm(busy: Boolean, onSubmit: (String, String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column {
        Text("手机号 + 密码", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { ch -> ch.isDigit() }.take(11) },
            label = { Text("11 位手机号", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码", fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onSubmit(phone, password) },
            enabled = !busy && phone.length == 11 && password.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("登录学习通", fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "密码只用于这一次登录请求；本机只保存登录态，不保存密码。",
            fontSize = 10.5.sp,
            color = InkTertiary,
        )
    }
}

/**
 * 网页登录入口：一行蓝色小字，点按直接进入应用内网页登录。
 *
 * 之前这里是一整段「标题 + 说明 + 大按钮」，太抢戏；密码登录失败时才需要它。
 */
@Composable
private fun WebLoginHint(busy: Boolean, onWebLogin: () -> Unit) {
    Text(
        "忘记密码？点此用网页登录。",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (busy) InkTertiary else BrandBlue,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !busy, onClick = onWebLogin)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun WebLoginSection(busy: Boolean, onWebLogin: () -> Unit) {
    Column {
        Text("应用内网页登录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            "在应用内打开平台官网登录，登录成功后自动取回 cookie，不用手动复制。",
            fontSize = 10.5.sp,
            color = InkTertiary,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onWebLogin,
            enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开网页登录", fontSize = 14.sp)
        }
    }
}

@Composable
private fun CookieForm(busy: Boolean, onSubmit: (String) -> Unit) {
    var cookie by remember { mutableStateOf("") }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(1.dp)
                    .weight(1f)
                    .background(HairLine)
            )
            Text(" 或手动粘贴 Cookie ", fontSize = 10.5.sp, color = InkTertiary)
            Box(
                Modifier
                    .height(1.dp)
                    .weight(1f)
                    .background(HairLine)
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            label = { Text("name=value; name2=value2", fontSize = 12.sp) },
            minLines = 3,
            maxLines = 5,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSubmit(cookie) },
            enabled = !busy && cookie.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("用这段 Cookie 登录", fontSize = 13.sp)
        }
    }
}

@Composable
private fun SessionForm(busy: Boolean, onSubmit: (String) -> Unit) {
    var session by remember { mutableStateOf("") }
    Column {
        Text("导入浏览器 session", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            "学在重邮只支持导入 session：在电脑浏览器登录 lms.tc.cqupt.edu.cn，" +
                "从开发者工具复制名为 session 的 cookie 值粘贴到这里。",
            fontSize = 10.5.sp,
            color = InkTertiary,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = session,
            onValueChange = { session = it.trim() },
            label = { Text("session 值", fontSize = 12.sp) },
            minLines = 3,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSubmit(session) },
            enabled = !busy && session.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("验证并导入", fontSize = 14.sp)
        }
    }
}

private fun loginMethods(platform: Platform): Set<Platform> = when (platform) {
    Platform.CHAOXING -> setOf(Platform.CHAOXING)
    Platform.YUKETANG -> setOf(Platform.YUKETANG)
    Platform.TRONCLASS -> setOf(Platform.TRONCLASS)
    Platform.PIGAI -> setOf(Platform.PIGAI)
}

private fun loginHint(platform: Platform): String = when (platform) {
    Platform.CHAOXING -> "学习通的作业列表是全局的，登录一次即可抓到所有课程的作业。"
    Platform.YUKETANG -> "雨课堂用网页登录取 sessionid；提交状态平台不对外暴露，所以统一显示「状态未知」。"
    Platform.TRONCLASS -> "学在重邮只支持导入浏览器 session，失效后需要重新导入。"
    Platform.PIGAI -> "批改网只支持微信扫码登录，所以在应用内打开官网登录页，扫码后自动取回 cookie。"
}

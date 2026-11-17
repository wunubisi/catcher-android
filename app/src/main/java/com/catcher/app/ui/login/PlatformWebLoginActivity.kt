package com.catcher.app.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.catcher.app.domain.model.Platform
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.Canvas
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary

/**
 * 应用内网页登录。
 *
 * 电脑端是用本地窗口展示二维码给手机扫；手机上没法扫自己的屏幕，
 * 所以改成「在应用内打开平台官网登录 → 从 WebView 的 CookieManager 取回 cookie」。
 * 登录过程仍然发生在平台官方页面上，应用不接触账号密码。
 */
class PlatformWebLoginActivity : ComponentActivity() {

    private var platform: Platform = Platform.CHAOXING

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platformId = intent.getStringExtra(EXTRA_PLATFORM)
        val parsed = platformId?.let { Platform.ofId(it) }
        if (parsed == null) {
            finish()
            return
        }
        platform = parsed

        setContent {
            val platformRef = platform
            val manager = remember { CookieManager.getInstance().also { it.setAcceptCookie(true) } }
            var webView by remember { mutableStateOf<WebView?>(null) }
            var currentUrl by remember { mutableStateOf(platformRef.webLoginUrl) }

            Column(
                Modifier
                    .fillMaxSize()
                    .background(Canvas)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardWhite)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "取消",
                        fontSize = 14.sp,
                        color = InkSecondary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                            .padding(6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${platformRef.displayName} 网页登录",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                        Text(
                            "登录完成后点右上角",
                            fontSize = 9.5.sp,
                            color = InkTertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "用这个登录",
                        fontSize = 14.sp,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                val cookies = harvestCookies(
                                    manager,
                                    platformRef,
                                    webView?.url ?: currentUrl,
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent()
                                        .putExtra(EXTRA_PLATFORM, platformRef.id)
                                        .putExtra(EXTRA_COOKIES, cookies),
                                )
                                finish()
                            }
                            .padding(6.dp),
                    )
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = false
                            settings.userAgentString = DESKTOP_LOGIN_UA
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { currentUrl = it }
                                }
                            }
                            webChromeClient = WebChromeClient()
                            webView = this
                            loadUrl(platformRef.webLoginUrl)
                        }
                    },
                )
            }
        }
    }

    /** 把平台相关的几个域上的 cookie 合并成一段 Cookie 串，交给适配器校验 */
    private fun harvestCookies(
        manager: CookieManager,
        platform: Platform,
        currentUrl: String,
    ): String {
        val urls = linkedSetOf(platform.cookieProbeUrl, platform.webLoginUrl, currentUrl)
        val merged = LinkedHashMap<String, String>()
        for (url in urls) {
            if (url.isBlank()) continue
            val header = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
            header.split(';').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isNotEmpty()) merged[name] = value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_COOKIES = "extra_cookies"

        /** 平台站点普遍对移动 UA 做简化跳转，登录页用桌面 UA 更接近电脑端表现 */
        const val DESKTOP_LOGIN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}

package com.catcher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.catcher.app.domain.model.Platform
import com.catcher.app.ui.CatcherScreen
import com.catcher.app.ui.HomeViewModel
import com.catcher.app.ui.login.PlatformWebLoginActivity
import com.catcher.app.ui.theme.CatcherTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as CatcherApp).container }

    private val vm: HomeViewModel by viewModels {
        HomeViewModel.factory(container.repository)
    }

    /** 网页登录回来后，把取到的 cookie 交给适配器校验并保存 */
    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val platform = data.getStringExtra(PlatformWebLoginActivity.EXTRA_PLATFORM)
            ?.let { Platform.ofId(it) } ?: return@registerForActivityResult
        val cookies = data.getStringExtra(PlatformWebLoginActivity.EXTRA_COOKIES).orEmpty()
        if (cookies.isBlank()) return@registerForActivityResult
        vm.loginFromWeb(platform, cookies)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatcherTheme {
                CatcherScreen(
                    vm = vm,
                    onWebLogin = { platform ->
                        webLoginLauncher.launch(
                            Intent(this, PlatformWebLoginActivity::class.java)
                                .putExtra(PlatformWebLoginActivity.EXTRA_PLATFORM, platform.id)
                        )
                    },
                )
            }
        }
    }
}

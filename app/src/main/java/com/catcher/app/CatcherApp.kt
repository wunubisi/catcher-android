package com.catcher.app

import android.app.Application
import android.content.Context
import com.catcher.app.data.CatcherRepository
import com.catcher.app.data.db.CatcherDao
import com.catcher.app.data.db.CatcherDatabase
import com.catcher.app.data.prefs.AppSettings
import com.catcher.app.data.remote.HttpEngine
import com.catcher.app.data.remote.chaoxing.ChaoxingClient
import com.catcher.app.data.remote.pigai.PigaiClient
import com.catcher.app.data.remote.tronclass.TronclassClient
import com.catcher.app.data.remote.yuketang.YuketangClient
import com.catcher.app.data.secret.SecretVault
import com.catcher.app.domain.AdapterRegistry

/**
 * 依赖装配。对应电脑端的 cmd/app/app.go：
 * 把 http 引擎、四个平台适配器、本地库、凭据加密、设置组装好，再交给界面。
 */
class AppContainer(context: Context) {

    private val http: HttpEngine = HttpEngine()

    val registry: AdapterRegistry = AdapterRegistry(
        listOf(
            ChaoxingClient(http),
            YuketangClient(http),
            TronclassClient(http),
            PigaiClient(http),
        )
    )

    private val database: CatcherDatabase = CatcherDatabase(context)
    private val dao: CatcherDao = CatcherDao(database)
    private val vault: SecretVault = SecretVault()
    val settings: AppSettings = AppSettings(context)

    val repository: CatcherRepository = CatcherRepository(dao, vault, registry, settings)
}

class CatcherApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

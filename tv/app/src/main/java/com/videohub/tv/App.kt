package com.videohub.tv

import android.app.Application
import com.videohub.tv.data.Prefs
import com.videohub.tv.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    val prefs: Prefs by lazy { Prefs(this) }

    val repo: Repo by lazy { Repo(prefs) }

    /** 应用级协程域：埋点式的后台写（历史记录）不随页面销毁中断 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

package cc.uukanshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import cc.uukanshu.di.RealAppContainer
import cc.uukanshu.ui.UukanshuApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as cc.uukanshu.App
        setContent {
            val container = remember(app) { RealAppContainer(app) }
            UukanshuApp(container = container, app = app)
        }
    }
}

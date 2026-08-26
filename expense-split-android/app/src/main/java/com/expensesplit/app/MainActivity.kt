package com.expensesplit.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.domain.model.ThemeMode
import com.expensesplit.app.ui.navigation.AppRoot
import com.expensesplit.app.ui.theme.ExpenseSplitTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash until preferences resolve, so the first frame is already the right theme.
        splashScreen.setKeepOnScreenCondition { viewModel.preferences.value == null }

        val deepLink = intent?.getStringExtra(EXTRA_DEEP_LINK)

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            ExpenseSplitTheme(
                themeMode = preferences?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = preferences?.dynamicColor ?: true,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(startDeepLink = deepLink)
                }
            }
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK = "extra_deep_link"
    }
}

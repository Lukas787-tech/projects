package com.expensesplit.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.expensesplit.app.core.AppLocales
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.notifications.Notifier
import com.expensesplit.app.notifications.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltAndroidApp
class ExpenseApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var categoryRepository: CategoryRepository
    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var notifier: Notifier

    /** Outlives any screen; used only for one-shot startup work. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()

        applicationScope.launch {
            categoryRepository.seedIfEmpty()

            val preferences = preferencesRepository.preferences.first()
            // Locale changes touch the resource configuration, so they belong on the main thread.
            withContext(Dispatchers.Main) {
                if (preferences.languageTag.isNotBlank()) {
                    AppLocales.apply(preferences.languageTag)
                }
            }

            if (preferences.notificationsEnabled) {
                workScheduler.scheduleAll()
            }
        }
    }
}

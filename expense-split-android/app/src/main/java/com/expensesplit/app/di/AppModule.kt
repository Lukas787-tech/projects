package com.expensesplit.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Exposes the application [Context] unqualified.
 *
 * Repositories take a plain `Context` rather than an `@ApplicationContext`-qualified one so they
 * stay constructible in unit tests without pulling in Hilt's qualifier annotations.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}

package com.expensesplit.app.di

import androidx.annotation.Nullable
import com.expensesplit.app.BuildConfig
import com.expensesplit.app.data.remote.api.CurrencyApi
import com.expensesplit.app.data.remote.api.StorePriceApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                // Request bodies can carry item names; never log them in a release build.
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            },
        )
        .build()

    @Provides
    @Singleton
    fun provideCurrencyApi(client: OkHttpClient, json: Json): CurrencyApi =
        Retrofit.Builder()
            .baseUrl(CurrencyApi.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CurrencyApi::class.java)

    /**
     * Null when no store-price endpoint is configured, which is the default. Price comparison then
     * runs purely off the user's own receipt history.
     */
    @Provides
    @Singleton
    @Nullable
    fun provideStorePriceApi(client: OkHttpClient, json: Json): StorePriceApi? {
        val baseUrl = BuildConfig.STORE_PRICE_API_URL
        if (baseUrl.isBlank()) return null

        return runCatching {
            Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(StorePriceApi::class.java)
        }.getOrNull()
    }
}

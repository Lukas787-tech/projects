package com.expensesplit.app.di

import com.expensesplit.app.domain.ocr.ReceiptParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    /**
     * The parser's fallback currency only matters when a receipt shows no symbol at all; the device
     * locale is the best guess available at construction time, and the scanner screen lets the user
     * override it before saving.
     */
    @Provides
    @Singleton
    fun provideReceiptParser(): ReceiptParser {
        val defaultCurrency = runCatching {
            java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
        }.getOrDefault("USD")
        return ReceiptParser(defaultCurrency)
    }
}

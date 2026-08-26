package com.expensesplit.app.data.remote.api

import com.expensesplit.app.data.remote.dto.ExchangeRatesDto
import retrofit2.http.GET
import retrofit2.http.Path

interface CurrencyApi {

    @GET("v6/latest/{base}")
    suspend fun latestRates(@Path("base") base: String): ExchangeRatesDto

    companion object {
        const val BASE_URL = "https://open.er-api.com/"
    }
}

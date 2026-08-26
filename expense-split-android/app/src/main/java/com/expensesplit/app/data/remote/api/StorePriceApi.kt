package com.expensesplit.app.data.remote.api

import com.expensesplit.app.data.remote.dto.StorePriceResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Optional local-store price feed. See [com.expensesplit.app.data.remote.dto.StorePriceResponseDto]
 * for the expected contract and the README for how to configure an endpoint.
 */
interface StorePriceApi {

    @GET("prices")
    suspend fun search(
        @Query("q") query: String,
        @Query("currency") currency: String,
        @Query("lat") latitude: Double? = null,
        @Query("lon") longitude: Double? = null,
        @Query("radius_km") radiusKm: Int = 15,
    ): StorePriceResponseDto
}

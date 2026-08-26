package com.expensesplit.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response shape of https://open.er-api.com/v6/latest/{base} — free, no API key required. */
@Serializable
data class ExchangeRatesDto(
    @SerialName("result") val result: String = "",
    @SerialName("base_code") val baseCode: String = "",
    @SerialName("time_last_update_unix") val updatedAtUnix: Long = 0,
    @SerialName("rates") val rates: Map<String, Double> = emptyMap(),
)

/**
 * Generic store-price feed contract.
 *
 * There is no universal free API for local grocery prices, so the app talks to a configurable
 * endpoint that returns this shape. Point [com.expensesplit.app.BuildConfig] at a partner feed, a
 * regional open-data source, or a self-hosted scraper; when nothing is configured the app falls
 * back to the user's own receipt history, which is the only source it needs to be useful.
 */
@Serializable
data class StorePriceResponseDto(
    @SerialName("query") val query: String = "",
    @SerialName("currency") val currency: String = "USD",
    @SerialName("offers") val offers: List<StoreOfferDto> = emptyList(),
)

@Serializable
data class StoreOfferDto(
    @SerialName("item_name") val itemName: String = "",
    @SerialName("store_name") val storeName: String = "",
    /** Price in major units, e.g. 2.49. Converted to minor units on ingest. */
    @SerialName("price") val price: Double = 0.0,
    @SerialName("currency") val currency: String? = null,
    /** ISO-8601 date the price was observed, e.g. 2026-04-18. */
    @SerialName("observed_on") val observedOn: String? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
)

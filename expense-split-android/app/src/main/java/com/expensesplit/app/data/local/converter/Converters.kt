package com.expensesplit.app.data.local.converter

import androidx.room.TypeConverter
import java.time.LocalDate

/**
 * Dates are persisted as epoch days (not millis) so that a "date of expense" never shifts when the
 * device changes time zone. Enums are persisted by name so new values can be added safely.
 */
class Converters {

    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(epochDay: Long?): LocalDate? = epochDay?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun stringListToString(values: List<String>?): String = values?.joinToString("|").orEmpty()

    @TypeConverter
    fun stringToStringList(value: String?): List<String> =
        value?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
}

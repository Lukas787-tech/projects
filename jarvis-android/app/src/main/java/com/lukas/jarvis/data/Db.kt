package com.lukas.jarvis.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain framework SQLite. No ORM and no annotation processor on purpose: the
 * schema is small, and this keeps the build free of code-generation plugins.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.create().forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Each step adds to what is there and drops nothing; see [Schema].
        Schema.upgrade(oldVersion, newVersion).forEach(db::execSQL)
    }

    companion object {
        const val NAME = "jarvis.db"
        const val VERSION = Schema.VERSION
    }
}

package com.craftool.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Small configuration bridge for the LSPosed-injected Cloud process. The two
 * apps have different UIDs, so Cloud cannot read or update CraftUi's private
 * files directly.
 */
class ConfigContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.i("CraftUi", "config provider $method from ${callingPackage ?: "unknown"}")
        return when (method) {
            "read_config" -> Bundle().apply { putString("json", NextPassConfigStore.serialized()) }
            "write_config" -> {
                val json = extras?.getString("json") ?: return null
                Bundle().apply { putBoolean("ok", NextPassConfigStore.saveSerialized(json)) }
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                       selectionArgs: Array<out String>?): Int = 0
}

package com.augt.localseek.indexing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppEntity
import com.augt.localseek.ml.DenseEncoder

class AppIndexer(private val context: Context) {
    private val appDao = AppDatabase.getInstance(context).appDao()

    suspend fun indexApps(denseEncoder: DenseEncoder?) {
        Log.d("AppIndexer", "Starting app indexing...")
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        Log.d("AppIndexer", "Found ${resolveInfos.size} launcher activities")
        
        val appsToInsert = resolveInfos.mapNotNull { resolveInfo ->
            try {
                val appInfo = resolveInfo.activityInfo.applicationInfo
                val appName = pm.getApplicationLabel(appInfo).toString()
                val packageName = appInfo.packageName
                val category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    appInfo.category.let { cat ->
                        when (cat) {
                            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "audio"
                            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "game"
                            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "image"
                            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "maps"
                            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "news"
                            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
                            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "social"
                            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "video"
                            else -> ""
                        }
                    }
                } else ""

                val textRepresentation = "$appName application $category $packageName".trim()
                
                AppEntity(
                    packageName = packageName,
                    appName = appName,
                    textRepresentation = textRepresentation
                )
            } catch (e: Exception) {
                Log.e("AppIndexer", "Failed to process app info", e)
                null
            }
        }
        
        Log.d("AppIndexer", "Prepared ${appsToInsert.size} apps for insertion")

        val appsWithEmbeddings = if (denseEncoder != null && appsToInsert.isNotEmpty()) {
            val embeddings = denseEncoder.encodeBatch(appsToInsert.map { it.textRepresentation })
            if (embeddings.size == appsToInsert.size) {
                appsToInsert.mapIndexed { index, app -> app.copy(embedding = embeddings[index]) }
            } else {
                appsToInsert
            }
        } else {
            appsToInsert
        }

        appDao.clearAll()
        appDao.insertAll(appsWithEmbeddings)
        Log.d("AppIndexer", "Indexed ${appsWithEmbeddings.size} apps")
    }
}

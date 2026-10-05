package com.augt.localseek.indexing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.ml.DenseEncoder

class AppIndexer(
    private val context: Context,
    private val container: AppContainer = (context.applicationContext as? LocalSeekApplication)?.appContainer
        ?: AppContainer(context.applicationContext)
) {
    private val appDao = container.database.appDao()

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
                val stableKey = IdentityUtils.appStableKey(packageName)

                AppEntity(
                    packageName = packageName,
                    appName = appName,
                    textRepresentation = textRepresentation,
                    stableKey = stableKey
                )
            } catch (e: Exception) {
                Log.e("AppIndexer", "Failed to process app info", e)
                null
            }
        }.distinctBy { it.stableKey }
        
        Log.d("AppIndexer", "Prepared ${appsToInsert.size} distinct apps for insertion")

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

        // Reconcile deletions and upsert within an atomic transaction
        val existingAppMap = appDao.getAllApps().associateBy { it.stableKey }
        val existingKeys = existingAppMap.keys
        val currentKeys = appsWithEmbeddings.map { it.stableKey }.toSet()

        val toDelete = (existingKeys - currentKeys).toList()

        // Preserve database IDs for existing entities to prevent ID rotation
        val toUpsert = appsWithEmbeddings.map { app ->
            val existing = existingAppMap[app.stableKey]
            if (existing != null) {
                app.copy(id = existing.id)
            } else {
                app
            }
        }

        container.database.withTransaction {
            if (toDelete.isNotEmpty()) {
                appDao.deleteByStableKeys(toDelete)
                Log.d("AppIndexer", "Reconciled ${toDelete.size} deleted apps")
            }
            if (toUpsert.isNotEmpty()) {
                appDao.insertAll(toUpsert)
            }
        }
        Log.d("AppIndexer", "Indexed ${toUpsert.size} apps (reconciled in place)")
    }
}

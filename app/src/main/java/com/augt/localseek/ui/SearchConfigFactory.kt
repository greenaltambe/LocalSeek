package com.augt.localseek.ui

import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.ui.settings.AppSettings

/**
 * Builds the [RetrievalConfig] used for a user search: [RetrievalConfig.SHIPPED] (registered arm E9 for text) plus the user
 * settings that switch parts on or off. Kept as a pure function so a unit test can compare the result with arm E9.
 */
object SearchConfigFactory {
    fun build(settings: AppSettings, imageSearchAvailable: Boolean, benchmarkMode: Boolean): RetrievalConfig =
        RetrievalConfig.SHIPPED.copy(
            enableDense = settings.enableDenseRetrieval,
            enableRerank = settings.enableReranking,
            enableQueryExpansion = settings.enableQueryExpansion,
            enableImage = imageSearchAvailable,
            returnTopK = settings.maxResults,
            fusionMode = if (settings.enablePerTypeNormalization) FusionMode.PER_TYPE_NORMALIZATION else RetrievalConfig.SHIPPED.fusionMode,
            benchmarkMode = benchmarkMode
        )
}

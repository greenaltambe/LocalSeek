package com.augt.localseek.data.study

/**
 * The statements of migration 19 to 20, identical to what Room generates for the three study entities (checked against
 * schemas/20.json by StudyMigrationTest). Every statement is a CREATE; nothing alters or drops an existing object.
 */
object StudySchema {
    val CREATE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `study_session` (`id` INTEGER NOT NULL, `participantCode` TEXT NOT NULL, `consentAt` INTEGER NOT NULL, " +
            "`stage` TEXT NOT NULL, `indexStartMs` INTEGER NOT NULL, `indexEndMs` INTEGER NOT NULL, `files` INTEGER NOT NULL, " +
            "`chunks` INTEGER NOT NULL, `apps` INTEGER NOT NULL, `contacts` INTEGER NOT NULL, `batteryStart` INTEGER NOT NULL, " +
            "`chargingStart` INTEGER NOT NULL, `batteryEnd` INTEGER NOT NULL, `chargingEnd` INTEGER NOT NULL, " +
            "`freeUseStartMs` INTEGER NOT NULL, `susAnswers` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `study_query` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `idx` INTEGER NOT NULL, " +
            "`queryText` TEXT NOT NULL, `kind` TEXT NOT NULL, `style` TEXT NOT NULL, `wordCount` INTEGER NOT NULL, " +
            "`seed` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `pooledCount` INTEGER NOT NULL, `latencyE1Ms` INTEGER NOT NULL, " +
            "`latencyE2Ms` INTEGER NOT NULL, `latencyE9Ms` INTEGER NOT NULL, `found` INTEGER NOT NULL, `completed` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_study_query_idx` ON `study_query` (`idx`)",
        "CREATE TABLE IF NOT EXISTS `study_judgment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `queryId` INTEGER NOT NULL, " +
            "`itemKey` TEXT NOT NULL, `entityType` TEXT NOT NULL, `rankE1` INTEGER NOT NULL, `rankE2` INTEGER NOT NULL, " +
            "`rankE9` INTEGER NOT NULL, `relevant` INTEGER)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_study_judgment_queryId_itemKey` ON `study_judgment` (`queryId`, `itemKey`)"
    )
}

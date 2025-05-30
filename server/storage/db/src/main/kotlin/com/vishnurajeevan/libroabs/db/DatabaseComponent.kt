package com.vishnurajeevan.libroabs.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.Properties

@ContributesTo(AppScope::class)
interface DatabaseComponent {
  @SingleIn(AppScope::class)
  @Provides
  fun driver(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:data.db", Properties(), Database.Schema)

  @SingleIn(AppScope::class)
  @Provides
  fun db(driver: SqlDriver) = Database(
    driver = driver,
    download_historyAdapter = Download_history.Adapter(EnumColumnAdapter())
  )

  @SingleIn(AppScope::class)
  @Provides
  fun downloadHistoryQueries(db: Database) = db.downloadHistoryQueries

  @SingleIn(AppScope::class)
  @Provides
  fun wishlistSyncQueries(db: Database) = db.wishlistSyncStatusQueries
}
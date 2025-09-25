package com.example.giftsbot.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.sqlobject.kotlin.KotlinSqlObjectPlugin
import javax.sql.DataSource

private const val DEFAULT_POOL_NAME = "giftsbot"
private const val DEFAULT_MAX_POOL_SIZE = 16
private const val LEAK_DETECTION_THRESHOLD_MILLIS = 60_000L

fun createHikariDataSource(
    url: String,
    user: String,
    password: String,
    poolName: String = DEFAULT_POOL_NAME,
): HikariDataSource {
    val config = HikariConfig()
    config.jdbcUrl = url
    config.username = user
    config.password = password
    config.poolName = poolName
    config.isAutoCommit = false
    config.maximumPoolSize = DEFAULT_MAX_POOL_SIZE
    config.transactionIsolation = "TRANSACTION_READ_COMMITTED"
    config.leakDetectionThreshold = LEAK_DETECTION_THRESHOLD_MILLIS
    return HikariDataSource(config)
}

fun createJdbi(dataSource: DataSource): Jdbi =
    Jdbi.create(dataSource).apply {
        installPlugin(KotlinPlugin())
        installPlugin(KotlinSqlObjectPlugin())
    }

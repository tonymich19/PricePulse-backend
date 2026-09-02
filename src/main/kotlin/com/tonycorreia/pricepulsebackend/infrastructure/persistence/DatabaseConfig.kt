package com.tonycorreia.pricepulsebackend.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

internal const val DATABASE_URL_ENV_VAR = "DATABASE_URL"
internal const val MONTHLY_FREE_CREDITS_ENV_VAR = "MONTHLY_FREE_CREDITS"

/**
 * Pure, offline-testable -- absent or blank fails before the pool is ever built. The failure
 * message names only [DATABASE_URL_ENV_VAR], never [rawValue], which carries the database user and
 * password.
 */
internal fun resolveDatabaseUrl(rawValue: String?): String {
    require(!rawValue.isNullOrBlank()) { "$DATABASE_URL_ENV_VAR must be set to a non-blank value" }
    return rawValue
}

/**
 * The monthly free credit allowance every [PostgresReceiptAnalysisOperationStore] grant starts
 * from. Deliberately configuration, never a constant: the "five credits per month" on record is a
 * product planning hypothesis, not a confirmed decision, so the number stays a deployment choice
 * and this code never invents one.
 *
 * Only a positive integer is accepted -- the store's own `require(monthlyFreeCredits > 0)` would
 * otherwise fail later, deep in the first grant, instead of here at boot.
 */
internal fun resolveMonthlyFreeCredits(rawValue: String?): Int {
    val parsed = rawValue?.toIntOrNull()
    require(parsed != null && parsed > 0) {
        "$MONTHLY_FREE_CREDITS_ENV_VAR must be set to a positive integer"
    }
    return parsed
}

/**
 * Built lazily on purpose: the no-argument constructor plus `jdbcUrl` defers pool creation to the
 * first [DataSource.getConnection] call, so constructing one opens no connection and needs no
 * reachable database. Pool size and timeouts stay at HikariCP's own defaults -- no number is
 * invented here.
 *
 * The caller owns the returned pool and must [HikariDataSource.close] it; that is why the concrete
 * type, not [DataSource], is returned -- [DataSource] declares no close.
 *
 * Any HikariCP/JDBC failure becomes a brand-new exception carrying a fixed message: the original is
 * never read, logged, chained as `cause`, or rethrown. Its message is written by HikariCP/the
 * driver, outside PricePulse's control, and can echo the whole URL -- and an exception that escapes
 * `main()` is printed by the runtime with its full cause chain, which would leak the credentials
 * just as surely as logging it.
 */
internal fun buildDataSource(url: String): HikariDataSource = try {
    HikariDataSource().apply { jdbcUrl = url }
} catch (_: Exception) {
    throw IllegalStateException("Could not build the connection pool for $DATABASE_URL_ENV_VAR")
}

/**
 * Runs every pending migration in `db/migration` once, before the server accepts requests, so a
 * failing schema stops the boot instead of surfacing later as a query error.
 *
 * Takes the interface: Flyway never needs the concrete pool type. Failures are sanitized exactly as
 * in [buildDataSource] -- Flyway wraps the driver's own exception, whose message can contain the
 * URL.
 */
internal fun migrate(dataSource: DataSource) {
    try {
        Flyway.configure().dataSource(dataSource).load().migrate()
    } catch (_: Exception) {
        throw IllegalStateException("Database migration failed for the database named by $DATABASE_URL_ENV_VAR")
    }
}

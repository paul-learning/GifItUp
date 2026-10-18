package app.gifitup.server.db

/**
 * What the end-to-end test (#79 PR C) needs to run the real `Migrate`, `Admin` and server processes: a fresh database
 * on the production image and the credentials those processes get through their secret files. Public in the test
 * fixtures only; `:server`'s main code keeps its visibility (#79 round-0 review).
 */
object EndToEndDatabase {
    /** A fresh, empty database. [toString] leaves the passwords out. */
    class Fresh internal constructor(
        val jdbcUrl: String,
        val adminUser: String,
        val adminPassword: String,
        val appUser: String,
        val appPassword: String,
    ) {
        override fun toString(): String = "EndToEndDatabase.Fresh(jdbcUrl=$jdbcUrl, adminUser=$adminUser, appUser=$appUser)"
    }

    fun fresh(): Fresh =
        Fresh(TestPostgres.freshDatabase(), TestPostgres.ADMIN_USER, TestPostgres.adminPassword, APP_ROLE, TestPostgres.appPassword)

    /** Everything PostgreSQL has logged so far, for the test's output checks. */
    fun serverLog(): String = TestPostgres.serverLog()
}

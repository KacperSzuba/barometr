package pl.barometr.platform

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * Gives `@SchedulerLock` something to lock with, and states this module's dependency
 * on scheduling out loud.
 *
 * ShedLock honours `@SchedulerLock` only through the interceptor that
 * `@EnableSchedulerLock` installs, and only if a [LockProvider] exists. Neither was
 * here, so the annotations on `IngestionScheduler.dispatchDueSources` and
 * `JobWorker.reclaimAbandoned` documented a guarantee that did not exist: two
 * instances would have dispatched the same sources at the same moment, silently,
 * and nothing in the build would have said so. `ApplicationContextTest` asserts it
 * now, because the failure mode is silence.
 *
 * `@EnableScheduling` is not strictly required — Boot's autoconfiguration already
 * registers the `@Scheduled` processor on this classpath, which a control run
 * confirmed. It is declared anyway: this module ships scheduled beans, and a module
 * that depends on scheduling should say so rather than inherit it by luck.
 *
 * Here rather than in the application, because providing background execution is
 * what this module is for.
 */
@Configuration
@EnableScheduling
// Long enough that a slow dispatch keeps its lock, short enough that an instance
// killed mid-task does not hold the lock for a shift. Individual tasks override it
// where their own duration is known.
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
class BackgroundWorkConfiguration {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                // Instances compare against the database's clock, not their own:
                // a lock is only meaningful if everyone agrees when it expires.
                .usingDbTime()
                .withTableName("platform.shedlock")
                .build(),
        )
}

package pl.barometr.platform.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.jobs")
data class JobWorkerProperties(
    val batchSize: Int = 8,
    /** A worker holding a job longer than this is presumed dead. */
    val abandonedAfter: Duration = Duration.ofMinutes(15),
)

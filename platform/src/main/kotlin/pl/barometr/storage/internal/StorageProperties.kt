package pl.barometr.storage.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    /** Root directory for the filesystem implementation. */
    val root: Path,
)

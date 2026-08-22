package pl.barometr.storage.internal

import pl.barometr.storage.StorageKind
import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where blobs are kept, and on what.
 *
 * The kind is stated rather than inferred from which other settings happen to be
 * present. Inferring it means a typo in a bucket prefix silently falls back to writing
 * the archive onto a container's disk, where it survives exactly as long as the
 * container — and nobody finds out until somebody asks for a document.
 */
@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    val kind: StorageKind = StorageKind.FILESYSTEM,
    /** Root directory for the filesystem implementation. Required when it is in use. */
    val root: Path? = null,
    val gcs: GcsProperties = GcsProperties(),
) {

    data class GcsProperties(
        /** The project the buckets belong to. Required for `kind=gcs`. */
        val project: String = "",
        /**
         * What the three bucket names start with.
         *
         * A prefix rather than three names, because a bucket name is global to all of
         * Google Cloud — `raw` was taken years ago by somebody else. `barometr` gives
         * `barometr-raw`, `barometr-derived`, `barometr-exports`, and a second
         * deployment picks a different prefix without touching any code.
         */
        val bucketPrefix: String = "barometr",
        /**
         * Where the buckets are made if they are not there. Only read when creating,
         * because an existing bucket keeps the location it was created in.
         */
        val location: String = "europe-central2",
        /**
         * An emulator's address. Empty means the real thing, reached with whatever
         * credentials the environment provides — a service account on a developer's
         * machine, the workload's own identity in the cluster, and no secret in either
         * case.
         */
        val endpoint: String = "",
    )
}

package pl.barometr.storage.internal

import com.google.cloud.NoCredentials
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.barometr.storage.BlobStore

/**
 * Which store the application runs on, decided by one property.
 *
 * On the property rather than on what else happens to be configured: inferring it means
 * a typo in a project name quietly falls back to writing the archive onto a container's
 * disk, where it lives exactly as long as the container. Stated, a missing project is a
 * refusal to start.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class BlobStoreConfiguration {

    @Bean
    @ConditionalOnProperty(name = ["app.storage.kind"], havingValue = "gcs")
    fun gcsBlobStore(properties: StorageProperties): BlobStore {
        val gcs = properties.gcs
        require(gcs.project.isNotBlank()) { "app.storage.gcs.project must be set for kind=gcs" }
        require(gcs.bucketPrefix.isNotBlank()) { "app.storage.gcs.bucket-prefix must not be blank" }

        return GcsBlobStore(storage(properties), gcs.bucketPrefix, gcs.location)
            .also { it.ensureBuckets() }
    }

    /**
     * The default, and the right one for a developer's machine: nothing to run, and the
     * blobs are somewhere they can be looked at.
     */
    @Bean
    @ConditionalOnProperty(name = ["app.storage.kind"], havingValue = "filesystem", matchIfMissing = true)
    fun filesystemBlobStore(properties: StorageProperties): BlobStore {
        val root = properties.root
        // Blank rather than absent is what an unset environment variable binds to, and
        // an empty path resolves to the working directory — which is inside the
        // container, which is the one place the archive must never be.
        require(root != null && root.toString().isNotBlank()) {
            "app.storage.root must be set for kind=filesystem"
        }
        return FilesystemBlobStore(root)
    }

    /**
     * Credentials come from the environment and never from configuration.
     *
     * On Google Cloud that is the workload's own identity; on a developer's machine it
     * is whatever `gcloud auth application-default login` left behind. Either way there
     * is no key in an environment variable to leak, which is the reason this uses
     * Google's own client rather than the S3 compatibility layer — that one needs HMAC
     * keys, which are exactly such a secret.
     *
     * An endpoint is set only against an emulator, which has no credentials to check.
     */
    private fun storage(properties: StorageProperties): Storage =
        StorageOptions.newBuilder()
            .setProjectId(properties.gcs.project)
            .apply {
                properties.gcs.endpoint.takeIf { it.isNotBlank() }?.let {
                    setHost(it)
                    setCredentials(NoCredentials.getInstance())
                }
            }
            .build()
            .service
}

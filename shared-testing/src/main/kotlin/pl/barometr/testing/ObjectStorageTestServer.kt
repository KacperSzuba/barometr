package pl.barometr.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * Object storage for the tests that use it.
 *
 * Google's own emulator image rather than a mock of the client: the adapter under test
 * makes the same API calls against the same protocol production uses, and a mock would
 * only confirm which methods were called. It is not Google Cloud — nothing on a laptop
 * is — but it is the same wire format, which is where an adapter goes wrong.
 *
 * Shared and started once, like the Postgres beside it, and capped like everything else
 * here.
 */
object ObjectStorageTestServer {

    private const val PORT = 4443

    private val container: GenericContainer<*> by lazy {
        GenericContainer("fsouza/fake-gcs-server:1.56.0")
            .withExposedPorts(PORT)
            // `-scheme http` because the default is TLS with a certificate no client
            // here trusts, and `-public-host` so the URLs it hands back point at the
            // mapped port rather than at its own idea of itself.
            .withCommand("-scheme", "http", "-port", "$PORT", "-backend", "memory")
            .waitingFor(Wait.forHttp("/storage/v1/b").forPort(PORT).forStatusCode(200))
            .withCreateContainerCmdModifier {
                it.withEntrypoint("/bin/fake-gcs-server")
                // A fraction of a core is plenty for a few objects, and this is sharing
                // the machine with a database and a build.
                it.hostConfig?.withNanoCPUs(CPU_SHARE)
            }
            .withStartupTimeout(STARTUP_TIMEOUT)
            .also { it.start() }
    }

    /** `http://host:port`, ready for `app.storage.gcs.endpoint`. */
    val endpoint: String get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    private const val CPU_SHARE = 1_000_000_000L

    private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(2)
}

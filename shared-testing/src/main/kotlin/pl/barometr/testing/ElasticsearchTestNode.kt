package pl.barometr.testing

import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * One Elasticsearch node for the module's tests, built from the project's own image.
 *
 * Built rather than pulled, because the thing under test is the Polish analyser and
 * that is a plugin the stock image does not carry. Testcontainers caches the built
 * image, so the cost is paid once per machine rather than once per run.
 *
 * Shared across test classes like the Postgres harness: a node costs seconds to start
 * and there is nothing in an index one test writes that another needs protecting from
 * — each test owns its own index name.
 */
object ElasticsearchTestNode {

    private val container: ElasticsearchContainer by lazy {
        // Built first, then run: Testcontainers' Elasticsearch container takes an
        // image name, and it checks that name against the official one — hence the
        // substitute declaration, which is the honest way to say "this is that image
        // with a plugin in it".
        val image = ImageFromDockerfile(IMAGE_NAME, false).withDockerfile(dockerfile()).get()

        ElasticsearchContainer(
            DockerImageName.parse(image).asCompatibleSubstituteFor(OFFICIAL_IMAGE),
        )
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            // Two cores. Indexing will use every one it is given, and it is sharing
            // the machine with a database, a build and whatever the developer is
            // doing — the same cap the compose file puts on the node they run locally.
            .withCreateContainerCmdModifier { it.hostConfig?.withNanoCPUs(CPU_SHARE) }
            // Testcontainers gives a container a minute to say it has started, and this
            // one is asked to start while the rest of the suite is holding a Postgres
            // per module. Under that, a node that takes ninety seconds is slow rather
            // than broken — and the failure it produced was a timeout on a node that
            // came up fine seconds later.
            .withStartupTimeout(STARTUP_TIMEOUT)
            .also { it.start() }
    }

    /** `http://host:port`, ready for `spring.elasticsearch.uris`. */
    val httpAddress: String get() = "http://${container.httpHostAddress}"

    /**
     * Found by walking up from wherever the test happens to run, because Gradle sets
     * the working directory to the module and the image belongs to the repository.
     * One place resolves it; the alternative is a relative path that is right for
     * exactly one module.
     */
    private fun dockerfile(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()

        while (directory != null) {
            val candidate = directory.resolve(DOCKERFILE)
            if (Files.exists(candidate)) return candidate
            directory = directory.parent
        }

        error("No $DOCKERFILE above ${Paths.get("").toAbsolutePath()}")
    }

    private const val DOCKERFILE = "infra/elasticsearch/Dockerfile"
    private const val IMAGE_NAME = "barometr/elasticsearch-polish:test"
    private const val OFFICIAL_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch"

    private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(3)

    /** Two cores, in the units Docker counts them: nanoseconds of CPU per second. */
    private const val CPU_SHARE = 2_000_000_000L

    /**
     * What a test that rebuilds the index holds while it runs.
     *
     * One node, one alias, and a rebuild points that alias at a fresh index — so two
     * such tests running at once would each be searching whatever the other had just
     * switched to. Unlike the databases, an index is not worth cloning per class: the
     * mapping is the thing under test, and building it is most of the cost.
     */
    const val INDEX_LOCK = "elasticsearch.legislative"
}

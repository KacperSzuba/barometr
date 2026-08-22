package pl.barometr.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A mutex, and nothing else.
 *
 * jOOQ's generation tool is not built for concurrent use, and code generation is fast
 * enough that serialising it costs nothing. It used to be the database service that
 * carried `maxParallelUsages = 1`, which was fine while only codegen used that service
 * — the moment the tests started sharing the container, that same limit would have run
 * every module's tests one after another.
 */
abstract class JooqCodegenLock : BuildService<BuildServiceParameters.None>

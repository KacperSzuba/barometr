plugins {
    id("barometr.spring-platform")
}

/**
 * Applied by every library module — the bounded contexts, `platform`, `shared` and
 * the test harness. Only `app` is exempt, because wiring the whole application
 * together is exactly its job.
 *
 * This plugin used to also fail the build when a module depended on another
 * module's `-impl` project. With one module per context there are no `-impl`
 * projects left to name, so the check has nothing to look at: a context's internals
 * are now hidden by package rather than by project.
 *
 * That moves enforcement from compile time to `check`, where Spring Modulith and
 * the ArchUnit rules in `ModularityTest` verify that nothing outside a context
 * reaches into its `internal` package. Weaker, and deliberate — the boundary worth
 * enforcing is the one a service extraction would follow, and that is the context,
 * not the layer.
 */

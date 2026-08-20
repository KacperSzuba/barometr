package pl.barometr.platform

/**
 * How urgently a job should be claimed, relative to everything else pending.
 *
 * An enum rather than the loose `Int` it replaces, where `priority = 42` compiled and
 * meant nothing to anyone. The number behind each level is what the claim query
 * orders by — lower first — and the gaps are deliberate: they leave room for a level
 * between two of these without renumbering the rows already in the queue.
 */
enum class JobPriority(val level: Int) {
    /** Somebody is waiting for the answer. */
    INTERACTIVE(10),

    /** Live ingestion and everything else the system does on its own schedule. */
    STANDARD(100),

    /**
     * Below live ingestion on purpose, so a five-year replay never delays today's
     * documents however many partitions of it are queued.
     */
    BACKGROUND(500),
}

package pl.barometr.connectors.sejm

/** A sitting, plus the number its votings hang off. */
class SejmProceeding internal constructor(
    val number: Int,
    val entity: SejmEntity,
)

package pl.barometr.taxonomy.internal

import java.util.UUID

/** How far the classifier has read into one kind of subject, and whether it is done. */
data class ClassificationProgress(
    val lastSubjectId: UUID?,
    val completed: Boolean,
)

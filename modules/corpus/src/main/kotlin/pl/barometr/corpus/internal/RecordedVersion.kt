package pl.barometr.corpus.internal

import pl.barometr.corpus.api.DocumentVersionId

/** A version the database accepted, and where it landed in the document's chain. */
data class RecordedVersion(val id: DocumentVersionId, val versionNo: Int)

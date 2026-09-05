package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.VersionDiff

/** One page of a comparison: what was compared, and the changes a reader is looking at. */
data class QuotedChanges(val diff: VersionDiff, val changes: List<QuotedChange>)

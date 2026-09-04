package pl.barometr.corpus.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.ingestion.api.ExternalId

/**
 * The context's read port. Everything crossing the boundary is an
 * [ArchivedDocument]; a jOOQ record leaving here would take the schema with it.
 */
@Component
@Transactional(readOnly = true)
class DocumentCatalogAdapter(private val documents: DocumentRepository) : DocumentCatalog {

    override fun documentById(id: DocumentId): ArchivedDocument? = documents.byId(id)

    override fun latestVersionAt(externalId: ExternalId): ArchivedVersion? = documents.latestVersionAt(externalId)

    override fun countByKind() = documents.countByKind()
}

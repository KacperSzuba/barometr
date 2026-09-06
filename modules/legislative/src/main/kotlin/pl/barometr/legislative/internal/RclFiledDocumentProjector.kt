package pl.barometr.legislative.internal

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded

/**
 * Ties an archived file back to the draft it was filed under.
 *
 * The second half of a filing, and the only half that makes one reachable: the catalog
 * page says a ministry filed "Projekt ustawy.pdf" on a Tuesday, and this says which
 * document in the corpus that is — so a reader can be shown the text, and what changed
 * in it since the last version.
 *
 * The address is the whole of what is needed, which is why nothing is read out of the
 * bytes here: RPL files a document under a project and a folder, and both are in the
 * id the archive stored it under.
 *
 * Every filed document is recorded, whatever it turns out to be. Which of them is the
 * bill and which is a table of comments is a question about the file's name, the card
 * answers it, and a listener that tried to decide it here would be guessing from an
 * address that does not say.
 */
@Service
class RclFiledDocumentProjector(private val filings: DraftFilingRepository) {

    @ApplicationModuleListener
    fun recordArchivedFiling(recorded: DocumentVersionRecorded) {
        if (recorded.kind != FILED_DOCUMENT) return

        val catalog = RclCatalogAddress.ofFiledDocument(recorded.externalId) ?: return
        val sourceDocument = RclCatalogAddress.filedDocumentIn(recorded.externalId) ?: return

        filings.recordArchivedFile(
            projectId = catalog.projectId,
            sourceDocument = sourceDocument,
            catalogId = catalog.catalogId,
            documentId = recorded.documentId,
        )
    }

    private companion object {
        /** `RclArchivedDocumentReader` names it; the address it is read from is the contract. */
        val FILED_DOCUMENT = DocumentKind("rcl-filed-document")
    }
}

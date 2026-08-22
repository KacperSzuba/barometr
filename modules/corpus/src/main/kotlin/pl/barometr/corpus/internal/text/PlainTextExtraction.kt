package pl.barometr.corpus.internal.text

import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * Plain text out of whatever a source filed.
 *
 * Apache Tika, per-format modules rather than the standard package: PDF and the
 * Microsoft formats are what RPL publishes, and nothing in Spring or the JDK reads
 * either. What is here is the twenty lines of integration around it, which is the
 * whole of what was ours to write.
 *
 * Two details are load-bearing rather than incidental.
 *
 * The write limit is removed. Tika's convenient facade truncates at a hundred
 * thousand characters, which is a third of a long bill — and the truncation is
 * silent, so every offset past it would point into text that exists in the file and
 * not in the archive.
 *
 * The type is detected from the bytes. The connector already prefers what the server
 * served over what the link was called; this is the last word on the question, taken
 * from the content itself, and it is what decides which parser runs.
 */
@Component
class PlainTextExtraction {

    private val parser = AutoDetectParser()

    fun readPlainText(payload: ByteArray): ExtractedText {
        val metadata = Metadata()
        // -1 removes the write limit; the default would truncate a long bill silently.
        val handler = BodyContentHandler(-1)

        ByteArrayInputStream(payload).use { bytes ->
            parser.parse(bytes, handler, metadata, ParseContext())
        }

        return ExtractedText(handler.toString(), metadata.get(Metadata.CONTENT_TYPE))
    }
}

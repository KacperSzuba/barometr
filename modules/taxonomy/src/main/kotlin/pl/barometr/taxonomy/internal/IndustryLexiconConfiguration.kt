package pl.barometr.taxonomy.internal

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper

/**
 * Loads the lexicon once, at startup, and announces what it loaded.
 *
 * At startup rather than per classification because the file is the same for the life
 * of the process and parsing it per title would be most of what classifying costs. It
 * is announced because which version is running is the first question asked of any
 * verdict that turns out to be wrong.
 */
@Configuration
class IndustryLexiconConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun industryLexicon(json: ObjectMapper): IndustryLexicon {
        val lexicon = IndustryLexicon.readFrom(ClassPathResource(LEXICON).inputStream, json)
        log.info("Industry lexicon {} loaded: {} terms", lexicon.version, lexicon.terms.size)

        return lexicon
    }

    private companion object {
        const val LEXICON = "taxonomy/pkd-lexicon.json"
    }
}

package pl.barometr

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind
import kotlin.test.assertEquals

/**
 * Every kind a module can raise becomes exactly one status, and every kind is covered.
 *
 * Written over the whole enum rather than over the cases somebody remembered: a kind
 * added without a status of its own is a compile error in the handler's `when`, but a
 * kind mapped to the wrong status is not, and this is what would notice.
 */
class ApiExceptionHandlerTest {

    private val handler = ApiExceptionHandler()

    @Test
    fun `a domain failure answers with its own code and the status its kind means`() {
        val expected = mapOf(
            ErrorKind.UNAUTHENTICATED to HttpStatus.UNAUTHORIZED,
            ErrorKind.FORBIDDEN to HttpStatus.FORBIDDEN,
            ErrorKind.NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorKind.CONFLICT to HttpStatus.CONFLICT,
            ErrorKind.INVALID to HttpStatus.BAD_REQUEST,
            // Not 403: a client that reads "forbidden" for "too soon" stops retrying a
            // request that would have worked in a quarter of an hour.
            ErrorKind.RATE_LIMITED to HttpStatus.TOO_MANY_REQUESTS,
        )

        assertEquals(ErrorKind.entries.toSet(), expected.keys, "every kind needs a status decided for it")

        expected.forEach { (kind, status) ->
            val response = handler.handleDomainException(Refusal(kind))

            assertEquals(status, response.statusCode, "status for $kind")
            assertEquals("refused", response.body?.error, "code for $kind")
        }
    }

    private class Refusal(kind: ErrorKind) : DomainException(kind, "refused")
}

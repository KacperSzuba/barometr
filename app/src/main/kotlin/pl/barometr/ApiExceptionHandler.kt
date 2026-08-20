package pl.barometr

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/** Error payload returned by every failing endpoint: `{ "error": "..." }`. */
data class ErrorResponse(val error: String, val details: Map<String, String>? = null)

/**
 * The one place where domain failures become HTTP.
 *
 * Modules throw [DomainException] carrying an [ErrorKind] and a stable code;
 * none of them import anything from `org.springframework.http`. That keeps the
 * same exception meaningful to a scheduled job or a CLI, where a status code
 * would mean nothing.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(exception: DomainException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(statusFor(exception.kind)).body(ErrorResponse(exception.code))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = exception.bindingResult.fieldErrors.associate { field ->
            field.field to (field.defaultMessage ?: "invalid")
        }
        return ResponseEntity.badRequest().body(ErrorResponse("validation_failed", details))
    }

    private fun statusFor(kind: ErrorKind): HttpStatus = when (kind) {
        ErrorKind.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
        ErrorKind.FORBIDDEN -> HttpStatus.FORBIDDEN
        ErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorKind.CONFLICT -> HttpStatus.CONFLICT
        ErrorKind.INVALID -> HttpStatus.BAD_REQUEST
    }
}

package pl.barometr.identity.internal.privacy

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import java.security.Principal
import java.util.UUID

/**
 * The two rights this system implements rather than describes: a copy of everything, and
 * an account that goes away.
 *
 * Both are the caller's own and neither takes a parameter saying whose — an account is
 * closed by the person who owns it, and the one case where somebody else has to
 * intervene is a support procedure with an audit entry rather than a route here.
 *
 * Every request through here is recorded by the application's audit filter, which is
 * what the specification asks for by "an audit entry on every request": these are
 * mutations, and the filter records every one of those by construction rather than
 * because somebody remembered.
 */
@RestController
@RequestMapping("/api/v1/me")
class AccountDataController(
    private val exports: AccountDataExports,
    private val closure: AccountClosure,
) {

    /**
     * Asks for a copy of everything. Answered with what to poll, not with a file: a
     * person with three years of alerts has an export worth megabytes, and the statutory
     * answer to "how long may this take" is a month rather than a second.
     */
    @PostMapping("/export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestExport(caller: Principal): ExportResponse = describe(exports.requestExport(callerOf(caller)))

    @GetMapping("/export")
    fun exports(caller: Principal): List<ExportResponse> = exports.exportsOf(callerOf(caller)).map(::describe)

    @GetMapping("/export/{id}")
    fun export(caller: Principal, @PathVariable id: UUID): ExportResponse =
        describe(exports.exportOf(callerOf(caller), id))

    @GetMapping("/export/{id}/content")
    fun content(caller: Principal, @PathVariable id: UUID): ResponseEntity<InputStreamResource> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            // Named, so that a browser saves it as a file rather than rendering a
            // megabyte of JSON at somebody.
            .header(HttpHeaders.CONTENT_DISPOSITION, """attachment; filename="barometr-$id.json"""")
            .body(InputStreamResource(exports.readExport(callerOf(caller), id)))

    /**
     * Closes the account, taking the password again first.
     *
     * The caller is already signed in, so this is not about identity — it is about
     * somebody who walked away from an unlocked laptop. What comes back is what each
     * context deleted and what it kept, because "your data is gone" is a claim somebody
     * is entitled to see the shape of.
     */
    @DeleteMapping
    fun closeAccount(caller: Principal, @Valid @RequestBody request: ClosureRequest): ClosureResponse =
        ClosureResponse(
            closure.closeAccount(callerOf(caller), request.password).map {
                CategoryResponse(it.category, it.deleted, it.kept)
            },
        )

    private fun describe(export: AccountDataExport) = ExportResponse(
        id = export.id,
        status = export.status.wireName,
        byteSize = export.byteSize,
        detail = export.detail,
        requestedAt = export.requestedAt.toString(),
        completedAt = export.completedAt?.toString(),
        expiresAt = export.expiresAt.toString(),
    )

    data class ClosureRequest(@field:NotBlank val password: String)

    data class ExportResponse(
        val id: UUID,
        val status: String,
        val byteSize: Long?,
        /** Why it failed, when it did. Null otherwise. */
        val detail: String?,
        val requestedAt: String,
        val completedAt: String?,
        /** After this the file is deleted, and so is the record of it. */
        val expiresAt: String,
    )

    data class ClosureResponse(val erased: List<CategoryResponse>)

    /** What one context deleted, and what it kept and why. */
    data class CategoryResponse(
        val category: String,
        val deleted: Map<String, Int>,
        val kept: Map<String, String>,
    )
}

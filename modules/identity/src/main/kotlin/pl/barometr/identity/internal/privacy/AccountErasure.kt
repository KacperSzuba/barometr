package pl.barometr.identity.internal.privacy

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.PersonalDataStore

/**
 * Closing an account, everywhere at once.
 *
 * **Every context that holds anything is asked, and none of them is named here.** Spring
 * hands over every [PersonalDataStore] on the classpath, so a context added next year is
 * included by existing, not by somebody remembering to add a line to this class. That is
 * the whole design: the failure mode of a cascade written by hand is a module nobody
 * updated, and it surfaces as a data-protection incident rather than as a compile error.
 *
 * **One transaction.** A deletion that half worked leaves an account that cannot sign in
 * and data that is still there, which is worse than either outcome on its own — so
 * either all of it goes or none of it does and the request fails loudly.
 *
 * What survives is not hidden: each context reports what it kept and why, and the caller
 * is told. Today that is the audit trail, whose entries are hash-chained and cannot be
 * removed without breaking the chain for everybody else, and the suppression list, which
 * exists to honour somebody's earlier request not to be mailed.
 */
@Service
class AccountErasure(
    private val stores: List<PersonalDataStore>,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun eraseAccount(user: UserId): List<ErasureReport> {
        // Identity last: everything else may still want to read the account while it
        // works out what it holds about it, and the account row is what the others'
        // foreign keys — where they have any — point at.
        val ordered = stores.sortedBy { if (it.category == IDENTITY) 1 else 0 }
        val reports = ordered.map { it.erasePersonalData(user.value) }

        val rows = reports.sumOf { it.rowsDeleted }
        meters.counter("identity.account.erased").increment()
        log.info(
            "Account {} erased: {} rows across {} contexts; kept {}",
            user.value,
            rows,
            reports.count { it.rowsDeleted > 0 },
            reports.flatMap { it.kept.keys },
        )

        return reports
    }

    private companion object {
        const val IDENTITY = "identity"
    }
}

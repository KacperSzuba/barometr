package pl.barometr.identity.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.api.UserSnapshot
import pl.barometr.identity.internal.user.Users

/**
 * The module's read port. Everything crossing the boundary is a `UserSnapshot`,
 * never a JPA entity — otherwise another module would end up coupled to this
 * one's storage shape and a column rename would ripple outward.
 */
@Component
@Transactional(readOnly = true)
class UserLookupAdapter(private val users: Users) : UserLookup {

    override fun findById(id: UserId): UserSnapshot? = users.byId(id.value)?.toSnapshot()

    override fun findByEmail(email: String): UserSnapshot? =
        users.byEmail(email.trim().lowercase())?.toSnapshot()
}

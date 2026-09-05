package pl.barometr.alerts.internal

/**
 * One message, ready to hand to a mail server.
 *
 * Both bodies, always. A text part is not a courtesy to old clients: a message with
 * only HTML scores worse with every spam filter there is, and the alerts this product
 * sends are exactly the kind that must not land in a junk folder.
 */
data class EmailMessage(
    val to: String,
    val subject: String,
    val text: String,
    val html: String,
    /**
     * Where one click stops the mail, sent as `List-Unsubscribe`.
     *
     * Not decoration: mail providers rank a sender partly on whether unsubscribing is
     * easy, and the ones who cannot find the link press "spam" instead — which costs
     * far more than the subscription did.
     *
     * Null for a message nobody may unsubscribe from. There is exactly one kind — a
     * warning that somebody has signed in on a device this account has not used —
     * because "stop telling me when my password is used elsewhere" is not a preference
     * this product offers, and offering it in a header would be offering it.
     */
    val unsubscribeUrl: String? = null,
)

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
     */
    val unsubscribeUrl: String,
)

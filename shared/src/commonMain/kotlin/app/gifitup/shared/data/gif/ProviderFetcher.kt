package app.gifitup.shared.data.gif

/** How one request to the GIF provider's API ended. Closed: nothing of the URL (it holds the key), headers or errors is kept. */
sealed interface ProviderAnswer {
    /** Any complete answer, whatever its status. [body] is empty unless the status is 200. */
    class Reply(
        val status: Int,
        val body: ByteArray,
        val retryAfterSeconds: Int?,
    ) : ProviderAnswer {
        override fun toString(): String = "ProviderAnswer.Reply($status)"
    }

    /** No answer: no network, a timeout, or any failure of the platform. */
    data object Failed : ProviderAnswer

    /** More than the cap, by the declared length or by what arrived. */
    data object TooLarge : ProviderAnswer
}

/**
 * One `GET` to the provider's API with the key **in the URL**, so the URL is a secret: no implementation may log it, put it
 * in an error, or send it anywhere but that request. No cookies, no `Referer`, no redirect is followed (a 3xx is a reply
 * with that status), the body stops at [maxBytes] while it streams, and any failure, including a platform `Error`, is
 * [ProviderAnswer.Failed] with the cause thrown away (an exception's message can quote the URL). Ktor's engine for Android
 * and the JVM; the browser's own `fetch()` on the web.
 */
fun interface ProviderFetcher {
    suspend fun get(
        url: String,
        maxBytes: Int,
    ): ProviderAnswer
}

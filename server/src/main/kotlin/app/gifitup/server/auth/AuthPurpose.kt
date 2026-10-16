package app.gifitup.server.auth

/** What an auth signature authorizes (ADR-0009). A closed set; [wire] is the byte in the signed content. */
enum class AuthPurpose(
    val wire: Int,
) {
    REGISTER(1),
    LOGIN(2),
}

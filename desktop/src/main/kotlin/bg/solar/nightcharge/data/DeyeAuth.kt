package bg.solar.nightcharge.data

data class DeyeAuth(
    val appId: String = "",
    val appSecret: String = "",
    val email: String = "",
    val passwordSha256: String = "",
    val stationId: Long = 0,
    val deviceSn: String = "",
    val token: String = "",
    val tokenExpiresAt: Long = 0,
) {
    val configured: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank() && email.isNotBlank() && passwordSha256.isNotBlank()
}

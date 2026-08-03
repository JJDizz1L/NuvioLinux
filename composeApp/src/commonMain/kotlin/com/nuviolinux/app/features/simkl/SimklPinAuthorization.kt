package com.nuviolinux.app.features.simkl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SimklPinResponse(
    val result: String? = null,
    val message: String? = null,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
)

internal data class SimklPendingPinAuthorization(
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresAtEpochMs: Long,
)

internal sealed interface SimklPinPollResult {
    data class Authorized(val accessToken: String) : SimklPinPollResult
    data object Pending : SimklPinPollResult
    data object Gone : SimklPinPollResult
    data object Failed : SimklPinPollResult
}

internal fun SimklPinResponse.toPendingAuthorization(
    nowEpochMs: Long,
): SimklPendingPinAuthorization? {
    if (!result.equals("OK", ignoreCase = true)) return null
    val code = userCode?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val url = verificationUri?.trim()?.takeIf(String::isNotEmpty)
        ?: verificationUrl?.trim()?.takeIf(String::isNotEmpty)
        ?: return null
    val lifetimeSeconds = expiresIn?.coerceAtLeast(1L) ?: 900L
    return SimklPendingPinAuthorization(
        userCode = code,
        verificationUrl = url,
        intervalSeconds = interval?.coerceAtLeast(1) ?: 5,
        expiresAtEpochMs = nowEpochMs + lifetimeSeconds * 1_000L,
    )
}

internal fun SimklPinResponse.toPollResult(): SimklPinPollResult = when {
    !deviceCode.isNullOrBlank() -> SimklPinPollResult.Gone
    result.equals("OK", ignoreCase = true) && !accessToken.isNullOrBlank() ->
        SimklPinPollResult.Authorized(accessToken.trim())
    result.equals("KO", ignoreCase = true) -> SimklPinPollResult.Pending
    else -> SimklPinPollResult.Failed
}

internal fun isSimklPinAuthorizationExpired(
    expiresAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = expiresAtEpochMs == null || nowEpochMs >= expiresAtEpochMs

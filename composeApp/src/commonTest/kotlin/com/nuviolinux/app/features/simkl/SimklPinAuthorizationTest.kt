package com.nuviolinux.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklPinAuthorizationTest {
    @Test
    fun `pin initialization uses documented code url interval and expiry`() {
        val pending = SimklPinResponse(
            result = "OK",
            deviceCode = "DEVICE_CODE",
            userCode = "ABCDE",
            verificationUri = "https://simkl.com/pin",
            verificationUrl = "https://legacy.example/pin",
            expiresIn = 900,
            interval = 5,
        ).toPendingAuthorization(nowEpochMs = 1_000L)

        assertEquals("ABCDE", pending?.userCode)
        assertEquals("https://simkl.com/pin", pending?.verificationUrl)
        assertEquals(5, pending?.intervalSeconds)
        assertEquals(901_000L, pending?.expiresAtEpochMs)
    }

    @Test
    fun `pin initialization accepts the legacy verification url alias`() {
        val pending = SimklPinResponse(
            result = "OK",
            userCode = "FGHIJ",
            verificationUrl = "https://simkl.com/pin",
        ).toPendingAuthorization(nowEpochMs = 2_000L)

        assertEquals("https://simkl.com/pin", pending?.verificationUrl)
        assertEquals(5, pending?.intervalSeconds)
        assertEquals(902_000L, pending?.expiresAtEpochMs)
    }

    @Test
    fun `invalid pin initialization responses are rejected`() {
        assertNull(SimklPinResponse(result = "KO").toPendingAuthorization(0L))
        assertNull(
            SimklPinResponse(
                result = "OK",
                userCode = "ABCDE",
            ).toPendingAuthorization(0L),
        )
    }

    @Test
    fun `pin polling follows documented response shapes`() {
        val authorized = SimklPinResponse(
            result = "OK",
            accessToken = "token",
        ).toPollResult()

        assertEquals("token", assertIs<SimklPinPollResult.Authorized>(authorized).accessToken)
        assertEquals(
            SimklPinPollResult.Pending,
            SimklPinResponse(result = "KO", message = "Authorization pending").toPollResult(),
        )
        assertEquals(
            SimklPinPollResult.Failed,
            SimklPinResponse(result = "OK").toPollResult(),
        )
    }

    @Test
    fun `fresh code response stops polling the original code`() {
        val result = SimklPinResponse(
            result = "OK",
            deviceCode = "DEVICE_CODE",
            userCode = "KLMNO",
            accessToken = "unexpected",
        ).toPollResult()

        assertEquals(SimklPinPollResult.Gone, result)
    }

    @Test
    fun `pin expiry uses the server supplied deadline`() {
        assertFalse(isSimklPinAuthorizationExpired(10_000L, 9_999L))
        assertTrue(isSimklPinAuthorizationExpired(10_000L, 10_000L))
        assertTrue(isSimklPinAuthorizationExpired(null, 1L))
    }
}

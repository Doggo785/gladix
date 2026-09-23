package dev.brahmkshatriya.echo.extensions.deezer

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression guard for the fresh-install login brick (found 2026-09-23 on the fork) and its
 * follow-up (rejected-credentials sessions hit the same wall):
 * [DeezerExtension.onExtensionSelected] used to rethrow [ClientException.LoginRequired] from
 * `handleArlExpiration`, and it runs inside the Injectable injection block, where a throw is
 * never cleared - every later `value()` re-ran it and re-threw. The login screen mapped the
 * failed capability query to "Login is not supported in Deezer" AND every later onLogin failed
 * on the same path, so login could never complete.
 *
 * Contract pinned here: `onExtensionSelected` NEVER throws LoginRequired (selection time is not
 * a fatal source), while `handleArlExpiration` itself still throws on the data paths - which is
 * what surfaces the sign-in prompt via ExceptionUtils. CancellationException still propagates.
 *
 * SCOPE NOTE: every branch under test throws-or-returns BEFORE any network (`api.makeUser()`
 * is never reached: the fresh branch throws at `else if (isArlExpired)`, the refused branch at
 * the latch check), so this runs fully offline on plain JUnit. State is the process-wide
 * [DeezerSession] singleton - reset in [tearDown] so no other test can observe it.
 */
class OnExtensionSelectedTest {

    private val session = DeezerSession.getInstance()

    private fun resetSession() {
        session.updateCredentials(
            arl = "", sid = "", token = "", userId = "",
            licenseToken = "", email = "", pass = ""
        )
        session.isArlExpired(false)
        session.setCredentialsRejected(false)
    }

    @After
    fun tearDown() = resetSession()

    @Test
    fun `never-logged-in selection does not throw`() = runBlocking {
        resetSession()
        try {
            DeezerExtension().onExtensionSelected()
        } catch (e: ClientException.LoginRequired) {
            fail(
                "onExtensionSelected threw LoginRequired on a never-logged-in session. " +
                    "That poisons the Injectable and bricks the login screen " +
                    "(\"Login is not supported\")."
            )
        }
    }

    @Test
    fun `refused credentials do not poison selection`() = runBlocking {
        resetSession()
        session.updateCredentials(email = "user@example.com", pass = "dead")
        session.setCredentialsRejected(true)
        try {
            DeezerExtension().onExtensionSelected()
        } catch (e: ClientException.LoginRequired) {
            fail(
                "onExtensionSelected threw LoginRequired on refused credentials. " +
                    "Same Injectable poison as the fresh-install brick: the login screen " +
                    "and onLogin would fail on every value()."
            )
        } finally {
            resetSession()
        }
    }

    @Test
    fun `refused credentials still throw on the data path`() = runBlocking {
        resetSession()
        session.updateCredentials(email = "user@example.com", pass = "dead")
        session.setCredentialsRejected(true)
        try {
            var thrown = false
            try {
                DeezerExtension().handleArlExpiration()
            } catch (e: ClientException.LoginRequired) {
                thrown = true
            }
            // The latch short-circuits before any network: this is what surfaces the
            // sign-in prompt from loads/playback via ExceptionUtils. If this stops
            // throwing, the "sign in again" affordance is gone with it.
            assertTrue(
                "handleArlExpiration must still throw LoginRequired on refused " +
                    "credentials - that throw is the sign-in prompt source.",
                thrown
            )
        } finally {
            resetSession()
        }
    }
}

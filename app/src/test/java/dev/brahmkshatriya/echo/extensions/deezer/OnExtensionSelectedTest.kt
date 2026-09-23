package dev.brahmkshatriya.echo.extensions.deezer

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression guard for the fresh-install login brick (found 2026-09-23 on the fork):
 * [DeezerExtension.onExtensionSelected] rethrows [ClientException.LoginRequired] from
 * `handleArlExpiration`, and it runs inside the Injectable injection block, where a throw is
 * never cleared - every later `value()` re-runs and re-throws. On a never-logged-in session
 * (empty credentials, no refusal) that mapped the login screen's capability query to
 * "Login is not supported in Deezer" AND made any later onLogin fail the same way.
 *
 * The fix narrows the rethrow to sessions that hold (or were refused) credentials. These two
 * cases pin both sides: fresh sessions must not throw, refused ones still must.
 *
 * SCOPE NOTE: both branches throw-or-return BEFORE any network (`api.makeUser()` is never
 * reached: the fresh branch throws at `else if (isArlExpired)`, the refused branch at the
 * latch check), so this runs fully offline on plain JUnit. State is the process-wide
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
    fun `never-logged-in session does not throw`() = runBlocking {
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

    @Test(expected = ClientException.LoginRequired::class)
    fun `refused credentials still throw`() = runBlocking {
        resetSession()
        session.updateCredentials(email = "user@example.com", pass = "dead")
        session.setCredentialsRejected(true)
        try {
            DeezerExtension().onExtensionSelected()
        } finally {
            resetSession()
        }
    }
}

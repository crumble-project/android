package dev.crumble.android

import dev.crumble.android.model.AppModel
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.InMemoryCredentialStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class SignOutTransport(private val status: Int) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        return HttpResponseData(status, "{}".toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SignOutTests {
    private fun store() = InMemoryCredentialStore(
        mapOf(
            "server-url" to "https://crumble.example",
            "server-token" to "session",
            "account-email" to "me@example.com",
            "recent-chat:abc:a1" to "{}",
            "push-state-v1" to "{}",
        ),
    )

    @Test
    fun signOutRevokesTheSessionThenForgetsIt() = runTest {
        val transport = SignOutTransport(200)
        val credentials = store()
        val order = mutableListOf<String>()
        val model = AppModel(
            client = APIClient("https://crumble.example", "session", transport),
            credentials = credentials,
            scope = this,
            transport = transport,
            onSignOut = { order.add("push ${transport.requests.size}") },
        )
        model.token = "session"
        model.password = "typed"
        model.signOut()
        runCurrent()

        assertEquals(listOf("push 0"), order)
        assertEquals("POST /api/auth/sign-out", transport.requests.single().let { "${it.method} ${it.pathWithQuery}" })
        assertEquals("Bearer session", transport.requests.single().headers["Authorization"])
        assertNull(credentials.read("server-token"))
        assertNull(credentials.read("account-email"))
        assertNull(credentials.read("recent-chat:abc:a1"))
        assertEquals("https://crumble.example", credentials.read("server-url"))
        assertEquals("{}", credentials.read("push-state-v1"))
        assertEquals("", model.token)
        assertEquals("", model.client?.token)
        assertEquals("", model.password)
        assertEquals("Signed out", model.notice)
    }

    @Test
    fun signOutStillForgetsTheSessionWhenTheServerFails() = runTest {
        val transport = SignOutTransport(503)
        val credentials = store()
        val model = AppModel(
            client = APIClient("https://crumble.example", "session", transport),
            credentials = credentials,
            scope = this,
            transport = transport,
        )
        model.token = "session"
        model.signOut()
        runCurrent()

        assertNull(credentials.read("server-token"))
        assertEquals("", model.token)
        assertEquals(
            "Signed out of this device. The server couldn't be reached, so the session may stay active there.",
            model.errorText,
        )
    }

    @Test
    fun anAlreadyExpiredSessionCountsAsSignedOut() = runTest {
        val transport = SignOutTransport(401)
        val model = AppModel(
            client = APIClient("https://crumble.example", "session", transport),
            credentials = store(),
            scope = this,
            transport = transport,
        )
        model.token = "session"
        model.signOut()
        runCurrent()

        assertEquals("Signed out", model.notice)
    }
}

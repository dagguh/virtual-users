package com.atlassian.performance.tools.virtualusers

import com.atlassian.performance.tools.jiraactions.api.ActionType
import com.atlassian.performance.tools.jiraactions.api.SeededRandom
import com.atlassian.performance.tools.jiraactions.api.WebJira
import com.atlassian.performance.tools.jiraactions.api.action.Action
import com.atlassian.performance.tools.jiraactions.api.measure.ActionMeter
import com.atlassian.performance.tools.jiraactions.api.memories.UserMemory
import com.atlassian.performance.tools.jiraactions.api.scenario.Scenario
import com.atlassian.performance.tools.virtualusers.api.VirtualUserLoad
import com.atlassian.performance.tools.virtualusers.api.VirtualUserOptions
import com.atlassian.performance.tools.virtualusers.api.browsers.Browser
import com.atlassian.performance.tools.virtualusers.api.browsers.CloseableRemoteWebDriver
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserBehavior
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserTarget
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status.OK
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.remote.RemoteWebDriver
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class LoadTestTerminationIT {

    private val load = VirtualUserLoad.Builder()
        .virtualUsers(3)
        .hold(Duration.ZERO)
        .ramp(Duration.ZERO)
        .flat(Duration.ofSeconds(21))
        .build()

    @Test
    fun shouldHaveReasonableOverheadDespiteSlowNavigations() {
        val loadTest = prepareLoadTest(SlowShutdownBrowser::class.java)

        val termination = testTermination(loadTest, "shouldHaveReasonableOverheadDespiteSlowNavigations")

        assertThat(termination.overhead).isLessThan(Duration.ofSeconds(2) + LoadSegment.DRIVER_CLOSE_TIMEOUT)
        assertThat(termination.blockingThreads).isEmpty()
    }

    @Test
    fun shouldCloseAFastBrowser() {
        val loadTest = prepareLoadTest(FastShutdownBrowser::class.java)

        val termination = testTermination(loadTest, "shouldCloseAFastBrowser")

        assertThat(termination.overhead).isLessThan(Duration.ofSeconds(2))
        assertThat(CLOSED_BROWSERS).contains(FastShutdownBrowser::class.java)
        assertThat(termination.blockingThreads).isEmpty()
    }

    private fun prepareLoadTest(
        browser: Class<out Browser>
    ): LoadTest {
        val options = VirtualUserOptions(
            target = VirtualUserTarget(
                webApplication = URI("http://doesnt-matter"),
                userName = "u",
                password = "p"
            ),
            behavior = VirtualUserBehavior.Builder(NavigatingScenario::class.java)
                .skipSetup(true)
                .browser(browser)
                .load(load)
                .build()
        )
        return LoadTest(
            options = options,
            userGenerator = SuppliedUserGenerator()
        )
    }

    private fun testTermination(
        test: LoadTest,
        label: String
    ): TerminationResult {
        val threadGroup = ThreadGroup(label)
        val threadName = "parent-for-$label"
        val testDuration = measureTimeMillis {
            Executors.newSingleThreadExecutor {
                Thread(threadGroup, it, threadName)
            }.submit {
                test.run()
            }.get()
        }
        return TerminationResult(
            overhead = Duration.ofMillis(testDuration) - load.total,
            blockingThreads = threadGroup.listBlockingThreads().filter { it.name != threadName }
        )
    }

    private class TerminationResult(
        val overhead: Duration,
        /**
         * If you want to find out who created these threads, you can debug with a breakpoint on [Thread.start]
         * and filter e.g. by [Thread.getName].
         */
        val blockingThreads: List<Thread>
    )

    private fun ThreadGroup.listBlockingThreads(): List<Thread> {
        return listThreads().filter { it.isDaemon.not() }
    }

    private fun ThreadGroup.listThreads(): List<Thread> {
        val threads = Array<Thread?>(activeCount()) { null }
        enumerate(threads)
        return threads.toList().filterNotNull()
    }
}

private val CLOSED_BROWSERS: MutableList<Class<*>> = mutableListOf()

private class SlowShutdownBrowser : SlowNavigationBrowser() {
    override val shutdown: Duration = Duration.ofSeconds(120)
}

private class FastShutdownBrowser : SlowNavigationBrowser() {
    override val shutdown: Duration = Duration.ofMillis(500)
}

private abstract class SlowNavigationBrowser : Browser {
    private val navigation: Duration = Duration.ofSeconds(10)
    abstract val shutdown: Duration

    override fun start(): CloseableRemoteWebDriver {
        val port = 8500 + PORT_OFFSET.getAndIncrement()
        val server = MockWebDriverServer(port, navigation)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val driver = RemoteWebDriver(server.base.toURL(), DesiredCapabilities())
        val clazz = this::class.java
        return object : CloseableRemoteWebDriver(driver) {
            override fun close() {
                super.close()
                Thread.sleep(shutdown.toMillis())
                server.stop()
                CLOSED_BROWSERS.add(clazz)
            }
        }
    }

    private companion object {
        val PORT_OFFSET = AtomicInteger(0)
    }
}

private class MockWebDriverServer(
    port: Int,
    private val navigation: Duration
) : NanoHTTPD(port) {
    internal val base = URI("http://localhost:$port")

    override fun serve(session: IHTTPSession): Response {
        val path = URI(session.uri).path
        return when {
            path == "/session/123/url" -> {
                Thread.sleep(navigation.toMillis())
                newFixedLengthResponse("")
            }
            path.startsWith("/session") -> {
                val sessionResponse = """
                {
                    "value": {
                        "sessionId": "123",
                        "capabilities": {}
                    }
                }
                """.trimIndent()
                newFixedLengthResponse(OK, "application/json", sessionResponse)
            }
            else -> super.serve(session)
        }

    }
}

private class NavigatingScenario : Scenario {

    override fun getLogInAction(
        jira: WebJira,
        meter: ActionMeter,
        userMemory: UserMemory
    ): Action = object : Action {
        override fun run() {}
    }

    override fun getActions(
        jira: WebJira,
        seededRandom: SeededRandom,
        meter: ActionMeter
    ): List<Action> = listOf(
        object : Action {
            private val navigation = ActionType("Navigation") { Unit }
            override fun run() {
                meter.measure(navigation) {
                    jira.navigateTo("whatever")
                }
            }
        }
    )
}

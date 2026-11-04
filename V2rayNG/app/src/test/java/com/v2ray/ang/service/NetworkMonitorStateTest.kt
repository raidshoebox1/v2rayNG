package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkMonitorStateTest {

    private val wifi = Any()
    private val cellular = Any()
    private val ethernet = Any()

    @Test
    fun `the first network is not a handover`() {
        val state = NetworkMonitorState()
        assertFalse(state.onAvailable(wifi))
    }

    @Test
    fun `a duplicate announcement is not a handover`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)
        assertFalse(state.onAvailable(wifi))
    }

    @Test
    fun `make before break announces the new network first`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)

        assertTrue(state.onAvailable(cellular))
        assertTrue(state.isUpstream(cellular))
        // The late onLost for the old network must not clear the new one.
        assertFalse(state.onLost(wifi))
        assertTrue(state.isUpstream(cellular))
    }

    @Test
    fun `break before make announces the new network after the gap`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)

        assertTrue(state.onLost(wifi))
        assertFalse(state.isUpstream(wifi))
        assertTrue(state.onAvailable(cellular))
    }

    @Test
    fun `the same network returning after a loss is a handover`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)
        state.onLost(wifi)

        assertTrue(state.onAvailable(wifi))
    }

    @Test
    fun `losing a network that is not the upstream is ignored`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)
        state.onAvailable(cellular)

        assertFalse(state.onLost(ethernet))
        assertTrue(state.isUpstream(cellular))
    }

    @Test
    fun `reset forgets the previous upstream`() {
        val state = NetworkMonitorState()
        state.onAvailable(wifi)
        state.reset()

        assertFalse(state.onAvailable(cellular))
    }
}

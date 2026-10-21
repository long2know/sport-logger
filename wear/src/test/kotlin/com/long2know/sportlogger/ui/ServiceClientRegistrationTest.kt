package com.long2know.sportlogger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceClientRegistrationTest {
    @Test
    fun registersExactlyOnceAndUnregistersOnLifecycleStop() {
        val service = Any()
        val registered = mutableListOf<Any>()
        val unregistered = mutableListOf<Any>()
        val registration = ServiceClientRegistration<Any>(
            register = registered::add,
            unregister = unregistered::add,
        )

        registration.bindingStarted()
        assertTrue(registration.serviceConnected(service))
        assertTrue(registration.serviceConnected(service))
        assertTrue(registration.isRegistered())

        registration.bindingStopped()

        assertEquals(listOf(service), registered)
        assertEquals(listOf(service), unregistered)
        assertFalse(registration.isRegistered())
    }

    @Test
    fun ignoresLateConnectionAfterActivityStopped() {
        val registered = mutableListOf<Any>()
        val registration = ServiceClientRegistration<Any>(
            register = registered::add,
            unregister = {},
        )

        registration.bindingStarted()
        registration.bindingStopped()

        assertFalse(registration.serviceConnected(Any()))
        assertTrue(registered.isEmpty())
    }

    @Test
    fun replacementConnectionCleansUpOldClientFirst() {
        val first = Any()
        val second = Any()
        val events = mutableListOf<String>()
        val registration = ServiceClientRegistration<Any>(
            register = { service ->
                events += if (service === first) "register-first" else "register-second"
            },
            unregister = { service ->
                events += if (service === first) "unregister-first" else "unregister-second"
            },
        )

        registration.bindingStarted()
        registration.serviceConnected(first)
        registration.serviceConnected(second)
        registration.bindingStopped()

        assertEquals(
            listOf(
                "register-first",
                "unregister-first",
                "register-second",
                "unregister-second",
            ),
            events,
        )
    }
}

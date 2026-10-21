package com.long2know.sportlogger.ui

class ServiceClientRegistration<T : Any>(
    private val register: (T) -> Unit,
    private val unregister: (T) -> Unit,
) {
    private var binding = false
    private var registered: T? = null

    fun bindingStarted() {
        binding = true
    }

    fun serviceConnected(service: T): Boolean {
        if (!binding) {
            return false
        }
        if (registered === service) {
            return true
        }
        registered?.let(unregister)
        register(service)
        registered = service
        return true
    }

    fun serviceDisconnected(service: T? = null) {
        if (service == null || registered === service) {
            registered = null
        }
    }

    fun bindingStopped() {
        binding = false
        registered?.let(unregister)
        registered = null
    }

    fun isRegistered(): Boolean = registered != null
}

package com.pixel.autodialer

object CallStateManager {
    enum class State {
        IDLE,
        CALLING,
        WAITING_NEXT,
        ANSWERED,
        STOPPED
    }

    var currentState: State = State.IDLE
        private set

    var targetPhoneNumber: String = ""
    var callTimeoutSeconds: Int = 20
    var redialDelaySeconds: Int = 3
    var attemptCount: Int = 0
    var isRunning: Boolean = false

    private val listeners = mutableListOf<(State, Int) -> Unit>()

    fun addListener(listener: (State, Int) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
            listener(currentState, attemptCount)
        }
    }

    fun removeListener(listener: (State, Int) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun updateState(newState: State) {
        currentState = newState
        notifyListeners()
    }

    fun incrementAttempt() {
        attemptCount++
        notifyListeners()
    }

    fun resetAttempts() {
        attemptCount = 0
        notifyListeners()
    }

    fun onCallAnswered() {
        isRunning = false
        currentState = State.ANSWERED
        notifyListeners()
    }

    fun stop() {
        isRunning = false
        currentState = State.STOPPED
        notifyListeners()
    }

    private fun notifyListeners() {
        synchronized(listeners) {
            for (listener in listeners) {
                listener(currentState, attemptCount)
            }
        }
    }
}

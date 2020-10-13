package com.bfergerson.vertx3.eventbus

data class EventBusOptions(
    val pingInterval: Int = 5000,
    val reconnectAttemptsMax: Int = Int.MAX_VALUE,
    val reconnectDelayMin: Int = 1000,
    val reconnectDelayMax: Int = 5000,
    val reconnectExponent: Int = 2,
    val randomizationFactor: Double? = 0.5
)

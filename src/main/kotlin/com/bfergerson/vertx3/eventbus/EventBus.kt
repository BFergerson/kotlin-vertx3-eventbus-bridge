package com.bfergerson.vertx3.eventbus

import SockJS
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import kotlin.js.Json
import kotlin.js.json
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class EventBus(val url: String, options: EventBusOptions = EventBusOptions()) {

    private var handlers: HashMap<String, MutableList<(Json?, Json?) -> Unit>> = HashMap()
    private var replyHandlers: HashMap<String, (Json?, Json?) -> Unit> = HashMap()
    private var defaultHeaders: Map<String, String> = HashMap()
    private var sockJSConn: WebSocket? = null
    private var state: EventBusStatus? = null
    private var pingInterval: Int = options.pingInterval
    private var pingTimerID: Int? = null
    private var reconnectEnabled: Boolean = false
    private var reconnectAttempts: Int = 0
    private var reconnectTimerID: Int? = null
    private var maxReconnectAttempts: Int = options.reconnectAttemptsMax
    private var reconnectDelayMin: Int = options.reconnectDelayMin
    private var reconnectDelayMax: Int = options.reconnectDelayMax
    private var reconnectExponent: Int = options.reconnectExponent
    private var randomizationFactor: Double? = options.randomizationFactor
    var onopen: (() -> Unit)? = null
    var onreconnect: (() -> Unit)? = null
    var onclose: ((Event) -> Unit)? = null
    var onerror: ((dynamic) -> Unit)? = null

    init {
        setupSockJSConnection()
    }

    private fun setupSockJSConnection() {
        sockJSConn = SockJS(url)//, null, options);
        state = EventBusStatus.CONNECTING

        // handlers and reply handlers are tied to the state of the socket
        // they are added onopen or when sending, so reset when reconnecting
        handlers.clear()
        replyHandlers.clear()

        sockJSConn!!.onopen = {
            enablePing(true)
            state = EventBusStatus.OPEN
            if (onopen != null) onopen!!.invoke()
            if (reconnectTimerID != null) {
                reconnectAttempts = 0
                // fire separate event for reconnects
                // consistent behavior with adding handlers onopen
                if (onreconnect != null) onreconnect!!.invoke()
            }
        }
        sockJSConn!!.onclose = {
            state = EventBusStatus.CLOSED
            if (pingTimerID != null) window.clearInterval(pingTimerID!!)
            if (reconnectEnabled && reconnectAttempts < maxReconnectAttempts) {
                sockJSConn = null
                // set id so users can cancel
                reconnectTimerID = window.setTimeout({ setupSockJSConnection() }, getReconnectDelay())
                ++reconnectAttempts
            }
            if (onclose != null) onclose!!.invoke(it)
        }
        sockJSConn!!.onmessage = { e ->
            val json = JSON.parse<dynamic>(e.data as String)

            // define a reply function on the message itself
            if (json.replyAddress != null) {
//                Object.defineProperty(json, 'reply', {
//                    value: function (message, headers, callback) {
//                    self.send(json.replyAddress, message, headers, callback);
//                }
//                });
            }

            if (handlers[json.address] != null) {
                // iterate all registered handlers
                val handlers = handlers[json.address]!!
                for (i in handlers.indices) {
                    if (json.type === "err") {
                        handlers[i].invoke(
                            json(
                                "failureCode" to json.failureCode,
                                "failureType" to json.failureType,
                                "message" to json.message
                            ), null
                        )
                    } else {
                        handlers[i].invoke(null, json)
                    }
                }
            } else if (replyHandlers[json.address] != null) {
                // Might be a reply message
                val handler = replyHandlers[json.address]!!
                replyHandlers.remove(json.address)
                if (json.type === "err") {
                    handler(
                        json(
                            "failureCode" to json.failureCode,
                            "failureType" to json.failureType,
                            "message" to json.message
                        ), null
                    )
                } else {
                    handler(null, json)
                }
            } else {
                if (json.type === "err") {
                    onerror!!.invoke(json)
                } else {
                    try {
                        console.warn("No handler found for message: ", json)
                    } catch (e: Throwable) {
                        // dev tools are disabled so we cannot use console on IE
                    }
                }
            }
        }
    }

    /**
     * Send a message
     *
     * @param {String} address
     * @param {Object} message
     * @param {Function} [callback]
     */
    fun send(
        address: String,
        message: dynamic,
        callback: ((Json?, Json?) -> Unit)?
    ) = send(address, message, null, callback)

    /**
     * Send a message
     *
     * @param {String} address
     * @param {Object} message
     * @param {Object} [headers]
     * @param {Function} [callback]
     */
    fun send(
        address: String,
        message: dynamic,
        headers: Map<String, String>? = null,
        callback: ((Json?, Json?) -> Unit)?
    ) {
        // are we ready?
        if (state != EventBusStatus.OPEN) {
            throw Error("INVALID_STATE_ERR")
        }

        val envelope = json(
            "type" to "send",
            "address" to address,
            "headers" to mergeHeaders(defaultHeaders, headers),
            "body" to message
        )

        if (callback != null) {
            val replyAddress = makeUUID()
            envelope["replyAddress"] = replyAddress
            replyHandlers[replyAddress] = callback
        }

        sockJSConn!!.send(JSON.stringify(envelope))
    }

    /**
     * Publish a message
     *
     * @param {String} address
     * @param {Object} message
     */
    fun publish(
        address: String,
        message: Any
    ) = publish(address, message, null)

    /**
     * Publish a message
     *
     * @param {String} address
     * @param {Object} message
     * @param {Object} [headers]
     */
    fun publish(address: String, message: Any, headers: Map<String, String>? = null) {
        // are we ready?
        if (this.state != EventBusStatus.OPEN) {
            throw Error("INVALID_STATE_ERR")
        }

        sockJSConn!!.send(
            JSON.stringify(
                json(
                    "type" to "publish",
                    "address" to address,
                    "headers" to mergeHeaders(defaultHeaders, headers),
                    "body" to message
                )
            )
        )
    }

    /**
     * Register a new handler
     *
     * @param {String} address
     * @param {Function} callback
     */
    fun registerHandler(
        address: String,
        callback: ((error: Json?, message: Json?) -> Unit)
    ) = registerHandler(address, null, callback)

    /**
     * Register a new handler
     *
     * @param {String} address
     * @param {Object} [headers]
     * @param {Function} callback
     */
    fun registerHandler(
        address: String,
        headers: Map<String, String>?,
        callback: ((error: Json?, message: Json?) -> Unit)
    ) {
        // are we ready?
        if (state != EventBusStatus.OPEN) {
            throw Error("INVALID_STATE_ERR")
        }

        // ensure it is an array
        if (handlers[address] == null) {
            handlers[address] = mutableListOf()
            // First handler for this address so we should register the connection
            sockJSConn!!.send(
                JSON.stringify(
                    json(
                        "type" to "register",
                        "address" to address,
                        "headers" to mergeHeaders(defaultHeaders, headers)
                    )
                )
            )
        }

        handlers[address]!!.add(callback)
    }

    /**
     * Unregister a handler
     *
     * @param {String} address
     * @param {Function} callback
     */
    fun unregisterHandler(
        address: String,
        callback: (() -> Unit)?
    ) = unregisterHandler(address, null, callback)

    /**
     * Unregister a handler
     *
     * @param {String} address
     * @param {Object} [headers]
     * @param {Function} callback
     */
    fun unregisterHandler(address: String, headers: Map<String, String>?, callback: (() -> Unit)?) {
        // are we ready?
        if (this.state != EventBusStatus.OPEN) {
            throw Error("INVALID_STATE_ERR")
        }

        val handlers = handlers[address]
        if (handlers != null) {
            val idx = handlers.indexOf(callback)
            if (idx != -1) {
                handlers.removeAt(idx)
                if (handlers.size == 0) {
                    // No more local handlers so we should unregister the connection
                    sockJSConn!!.send(
                        JSON.stringify(
                            json(
                                "type" to "unregister",
                                "address" to address,
                                "headers" to mergeHeaders(this.defaultHeaders, headers)
                            )
                        )
                    )

                    this.handlers.remove(address)
                }
            }
        }
    }

    /**
     * Closes the connection to the eventbus.EventBus Bridge,
     * preventing any reconnect attempts
     */
    fun close() {
        state = EventBusStatus.CLOSING
        enableReconnect(false)
        sockJSConn!!.close()
    }

    private fun enablePing(enable: Boolean) {
        if (enable) {
            val sendPing = {
                sockJSConn!!.send(JSON.stringify(json("type" to "ping")))
            }

            if (pingInterval > 0) {
                // Send the first ping then send a ping every pingInterval milliseconds
                sendPing()
                pingTimerID = window.setInterval(sendPing, pingInterval)
            }
        } else {
            if (pingTimerID != null) {
                window.clearInterval(pingTimerID!!)
                pingTimerID = null
            }
        }
    }

    private fun enableReconnect(enable: Boolean) {
        reconnectEnabled = enable
        if (!enable && reconnectTimerID != null) {
            window.clearTimeout(reconnectTimerID!!)
            reconnectTimerID = null
            reconnectAttempts = 0
        }
    }

    //todo: bad headers
    private fun mergeHeaders(defaultHeaders: Map<String, String>?, headers: Map<String, String>?): Map<String, String> {
        if (defaultHeaders != null) {
            if (headers == null) {
                return emptyMap()
                //return defaultHeaders //todo: this
            }

//            for (var headerName in defaultHeaders) {
//                if (defaultHeaders.hasOwnProperty(headerName)) {
//                    // user can overwrite the default headers
//                    if (typeof headers[headerName] === 'undefined') {
//                        headers[headerName] = defaultHeaders[headerName];
//                    }
//                }
//            }
        }

        // headers are required to be a object
        return headers!!
    }

    private fun makeUUID(): String {
        var uuid = ""
        for (ii in 0 until 32) {
            uuid += when (ii) {
                8, 20 -> "-${((Random.nextDouble() * 16).toInt() or 0).toString(16)}"
                12 -> "-4"
                16 -> "-${((Random.nextDouble() * 4).toInt() or 8).toString(16)}"
                else -> ((Random.nextDouble() * 16).toInt() or 0).toString(16)
            }
        }
        return uuid
    }

    private fun getReconnectDelay(): Int {
        var ms = reconnectDelayMin * reconnectExponent.toDouble().pow(reconnectAttempts.toDouble())
        if (randomizationFactor != null) {
            val rand = Random.nextDouble()
            val deviation = floor(rand * randomizationFactor!! * ms)
            ms = if ((floor(rand * 10).toInt() and 1) == 0) ms - deviation else ms + deviation
        }
        return min(ms.toInt(), reconnectDelayMax) or 0
    }
}

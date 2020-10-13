import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import kotlin.js.Json
import kotlin.js.json
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

fun main() {
    val eb = EventBus("http://localhost:8888/eventbus")
    eb.onopen = {
        eb.registerHandler("null-UpdateChart", null) { error: Json?, message: Json? ->
            println("got update chart!")
            document.write(JSON.stringify(message))
        }

        eb.publish("ActivityTabOpened", json("portalUuid" to "null"))
    }
}

class EventBus(val url: String, options: EventBusOptions = EventBusOptions()) {

    var handlers: HashMap<String, MutableList<(Json?, Json?) -> Unit>> = HashMap()
    var replyHandlers: HashMap<String, (Json?, Json?) -> Unit> = HashMap()
    var defaultHeaders: Map<String, String> = HashMap()

    var sockJSConn: WebSocket? = null
    var state: EventBusStatus? = null

    var pingInterval: Int
    var pingTimerID: Int? = null
    var reconnectEnabled: Boolean
    var reconnectAttempts: Int
    var reconnectTimerID: Int? = null
    var maxReconnectAttempts: Int
    var reconnectDelayMin: Int
    var reconnectDelayMax: Int
    var reconnectExponent: Int
    var randomizationFactor: Double? = null

    init {
        // attributes
        this.pingInterval = options.vertxbus_ping_interval
        this.pingTimerID = null

        this.reconnectEnabled = false
        this.reconnectAttempts = 0
        this.reconnectTimerID = null
        // adapted from backo
        this.maxReconnectAttempts = options.vertxbus_reconnect_attempts_max
        this.reconnectDelayMin = options.vertxbus_reconnect_delay_min
        this.reconnectDelayMax = options.vertxbus_reconnect_delay_max
        this.reconnectExponent = options.vertxbus_reconnect_exponent
        this.randomizationFactor = options.vertxbus_randomization_factor

        setupSockJSConnection()
    }

    var onopen: (() -> Unit)? = null

    fun setupSockJSConnection() {
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
                //self.onreconnect && self.onreconnect();
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
            //self.onclose && self.onclose(e);
        }
        sockJSConn!!.onmessage = { e ->
            var json = JSON.parse<dynamic>(e.data as String)

            // define a reply function on the message itself
            if (json.replyAddress) {
//                Object.defineProperty(json, 'reply', {
//                    value: function (message, headers, callback) {
//                    self.send(json.replyAddress, message, headers, callback);
//                }
//                });
            }

            if (handlers[json.address] != null) {
                // iterate all registered handlers
                var handlers = handlers[json.address]!!
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
                var handler = replyHandlers[json.address]!!
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
                    //onerror(json)
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

//        if (typeof headers === "function") {
//            callback = headers;
//            headers = {};
//        }

        var envelope = json(
            "type" to "send",
            "address" to address,
            "headers" to mergeHeaders(defaultHeaders, headers),
            "body" to message
        )

        if (callback != null) {
            var replyAddress = makeUUID()
            envelope.set("replyAddress", replyAddress)
            replyHandlers[replyAddress] = callback
        }

        sockJSConn!!.send(JSON.stringify(envelope))
    }

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

        this.sockJSConn!!.send(
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

//        if (typeof headers === 'function') {
//        callback = headers;
//        headers = {};
//    }

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
//            if (typeof headers === 'function') {
//                callback = headers;
//                headers = {};
//            }

            val idx = handlers.indexOf(callback)
            if (idx != -1) {
                handlers.removeAt(idx)
                if (handlers.size == 0) {
                    // No more local handlers so we should unregister the connection
                    this.sockJSConn!!.send(
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
     * Closes the connection to the EventBus Bridge,
     * preventing any reconnect attempts
     */
    fun close() {
        state = EventBusStatus.CLOSING
        enableReconnect(false)
        sockJSConn!!.close()
    }

    private fun enablePing(enable: Boolean) {
        var self = this

        if (enable) {
            var sendPing = {
                self.sockJSConn!!.send(JSON.stringify(json("type" to "ping")))
            }

            if (self.pingInterval > 0) {
                // Send the first ping then send a ping every pingInterval milliseconds
                sendPing()
                self.pingTimerID = window.setInterval(sendPing, self.pingInterval)
            }
        } else {
            if (self.pingTimerID != null) {
                window.clearInterval(self.pingTimerID!!)
                self.pingTimerID = null
            }
        }
    }

    private fun enableReconnect(enable: Boolean) {
        var self = this

        self.reconnectEnabled = enable
        if (!enable && self.reconnectTimerID != null) {
            window.clearTimeout(self.reconnectTimerID!!)
            self.reconnectTimerID = null
            self.reconnectAttempts = 0
        }
    }


    private fun mergeHeaders(defaultHeaders: Map<String, String>?, headers: Map<String, String>?): Map<String, String> {
        if (defaultHeaders != null) {
            if (headers == null) {
                return defaultHeaders
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

enum class EventBusStatus {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}

data class EventBusOptions(
    val vertxbus_ping_interval: Int = 5000,
    val vertxbus_reconnect_attempts_max: Int = Int.MAX_VALUE,
    val vertxbus_reconnect_delay_min: Int = 1000,
    val vertxbus_reconnect_delay_max: Int = 5000,
    val vertxbus_reconnect_exponent: Int = 2,
    val vertxbus_randomization_factor: Double? = 0.5
)

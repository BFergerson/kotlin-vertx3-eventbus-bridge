import com.bfergerson.vertx3.eventbus.EventBus
import kotlinx.browser.document
import kotlin.js.Json

fun main() {
    val eb = EventBus("http://localhost:8888/eventbus")
    eb.onopen = {
        println("connected")
        eb.send("ClickedViewAsExternalPortal", "hello", null) { error: Json?, message: Json? ->
            println("got reply")
            document.write(JSON.stringify(message))
        }
//        eb.registerHandler("null-UpdateChart", null) { error: Json?, message: Json? ->
//            println("got update chart!")
//            document.write(JSON.stringify(message))
//        }
//
//        eb.publish("ActivityTabOpened", json("portalUuid" to "null"))
    }
}

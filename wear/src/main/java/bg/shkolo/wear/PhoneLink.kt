package bg.shkolo.wear

import android.content.Context
import android.util.Log
import bg.shkolo.common.Protocol
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Watch -> phone transport over the Wearable Message API.
 *
 * One request = one JSON message. The phone replies on PATH_RESPONSE with the
 * same id, which we match back to a waiting coroutine.
 */
class PhoneLink(context: Context) {

    companion object {
        private const val TAG = "PhoneLink"
        private const val TIMEOUT_MS = 60_000L
    }

    data class Reply(val status: Int, val body: String)

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** id -> continuation receiver for in-flight requests. */
    private val pending = ConcurrentHashMap<String, (Reply) -> Unit>()

    @Volatile
    private var nodeId: String? = null

    /** Feed every MessageEvent here from the activity/service. */
    fun onMessage(event: MessageEvent) {
        if (event.path != Protocol.PATH_RESPONSE) return
        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optString(Protocol.KEY_ID)
        val callback = pending.remove(id) ?: return
        callback(Reply(json.optInt(Protocol.KEY_STATUS, 500), json.optString(Protocol.KEY_BODY)))
    }

    fun onNodeConnected(id: String) {
        nodeId = id
        Log.d(TAG, "node connected: $id")
    }

    fun onNodeLost(id: String) {
        if (nodeId == id) nodeId = null
    }

    /** Ask the phone for a path; returns PHONE_UNREACHABLE if no node. */
    suspend fun request(path: String, query: String = ""): Reply {
        val target = nodeId ?: findNode()
            ?: return Reply(Protocol.State.PHONE_UNREACHABLE, """{"error":"Phone not connected"}""")

        val id = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put(Protocol.KEY_ID, id)
            put(Protocol.KEY_PATH, path)
            put(Protocol.KEY_QUERY, query)
        }.toString().toByteArray(Charsets.UTF_8)

        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Reply> { cont ->
                pending[id] = { r -> if (cont.isActive) cont.resume(r) }

                messageClient.sendMessage(target, Protocol.PATH_REQUEST, payload)
                    .addOnSuccessListener { Log.d(TAG, "sent $path -> $target") }
                    .addOnFailureListener { e ->
                        pending.remove(id)
                        if (cont.isActive) {
                            cont.resume(Reply(Protocol.State.FAILED, """{"error":"${e.message}"}"""))
                        }
                    }

                cont.invokeOnCancellation { pending.remove(id) }
            }
        }

        pending.remove(id)

        return reply ?: Reply(Protocol.State.FAILED, """{"error":"Phone did not answer in time"}""")
    }

    private suspend fun findNode(): String? = suspendCancellableCoroutine { cont ->
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes: List<Node> ->
                val id = nodes.firstOrNull()?.id
                nodeId = id
                cont.resume(id)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }
}

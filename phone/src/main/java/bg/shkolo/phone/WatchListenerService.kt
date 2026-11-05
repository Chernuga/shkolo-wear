package bg.shkolo.phone

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import bg.shkolo.common.Protocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Receives requests from the watch and answers them.
 *
 * The watch is a pure view: it sends a path, the phone does the Shkolo work.
 * Replies go to the source node so multiple watches stay independent.
 */
class WatchListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchListener"
    }

    private val service by lazy { ShkoloService(applicationContext) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.PATH_REQUEST) return

        val payload = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
        val request = runCatching { JSONObject(payload) }.getOrNull() ?: return

        val id = request.optString(Protocol.KEY_ID, "")
        val path = request.optString(Protocol.KEY_PATH, "")
        val query = request.optString(Protocol.KEY_QUERY, "")

        Log.d(TAG, "request $path ($query) from ${event.sourceNodeId}")

        reply(id, path, query, event.sourceNodeId)
    }

    private fun reply(id: String, path: String, query: String, nodeId: String) {
        val result = runCatching { runBlocking { service.handle(path, query) } }
            .getOrElse {
                Log.e(TAG, "handler failed", it)
                ShkoloService.Reply(Protocol.State.FAILED, """{"error":"${it.message}"}""")
            }

        val body = JSONObject().apply {
            put(Protocol.KEY_ID, id)
            put(Protocol.KEY_STATUS, result.status)
            put(Protocol.KEY_BODY, result.body)
        }.toString()

        Wearable.getMessageClient(this)
            .sendMessage(nodeId, Protocol.PATH_RESPONSE, body.toByteArray(Charsets.UTF_8))
            .addOnFailureListener { e -> Log.e(TAG, "reply failed", e) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ShkoloClient.get(applicationContext).destroy() }
    }
}

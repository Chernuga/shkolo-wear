package bg.shkolo.phone

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import bg.shkolo.common.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Phone entry point.
 *
 * Login happens here, not on the watch: the watch keyboard is miserable and
 * the password would otherwise cross the link. The phone keeps the session.
 */
class MainActivity : AppCompatActivity() {

    private val client by lazy { ShkoloClient.get(this) }
    private val service by lazy { ShkoloService(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val user = findViewById<EditText>(R.id.username)
        val pass = findViewById<EditText>(R.id.password)
        val loginBtn = findViewById<Button>(R.id.loginButton)
        val logoutBtn = findViewById<Button>(R.id.logoutButton)
        val status = findViewById<TextView>(R.id.status)
        val progress = findViewById<ProgressBar>(R.id.progress)

        fun render() {
            val loggedIn = client.isLoggedIn
            status.text = if (loggedIn) {
                "✓ Свързан с Shkolo" + (client.pupilId?.let { " · ученик $it" } ?: "")
            } else {
                "○ Не сте влезли"
            }
            logoutBtn.visibility = if (loggedIn) View.VISIBLE else View.GONE
        }

        loginBtn.setOnClickListener {
            val u = user.text.toString().trim()
            val p = pass.text.toString()
            if (u.isBlank() || p.isBlank()) {
                status.text = "Въведете потребител и парола"
                return@setOnClickListener
            }

            loginBtn.isEnabled = false
            progress.visibility = View.VISIBLE
            status.text = "Влизане… (зарежда се страницата на Shkolo)"

            // Show elapsed time so a slow load never looks like a freeze.
            val started = System.currentTimeMillis()
            val ticker = lifecycleScope.launch(Dispatchers.Main) {
                while (true) {
                    delay(1000)
                    val s = (System.currentTimeMillis() - started) / 1000
                    status.text = "Влизане… ${s}s"
                }
            }

            lifecycleScope.launch {
                // Hard cap: never let the UI wait forever.
                val reply = withTimeoutOrNull(120_000) {
                    service.handle(
                        "/api/login",
                        "u=" + android.net.Uri.encode(u) + "&p=" + android.net.Uri.encode(p),
                    )
                }

                ticker.cancel()
                progress.visibility = View.GONE
                pass.text.clear()
                loginBtn.isEnabled = true

                status.text = when {
                    reply == null -> "✗ Изтече времето. Проверете връзката и опитайте пак."
                    reply.status == 200 -> "✓ Влязохте. Отворете приложението на часовника."
                    else -> "✗ Неуспешен вход: " +
                        runCatching { JSONObject(reply.body).optString("error") }.getOrDefault("unknown")
                }
                render()
            }
        }

        logoutBtn.setOnClickListener {
            lifecycleScope.launch {
                service.handle("/api/logout", "")
                render()
            }
        }

        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Keep the WebView: the whole point is a warm session for the watch.
    }
}

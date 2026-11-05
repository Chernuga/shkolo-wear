package bg.shkolo.phone

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import bg.shkolo.common.Protocol
import kotlinx.coroutines.launch
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
            status.text = "Влизане през браузъра на телефона…"

            lifecycleScope.launch {
                val reply = service.handle(
                    "/api/login",
                    "u=" + android.net.Uri.encode(u) + "&p=" + android.net.Uri.encode(p),
                )
                pass.text.clear()
                loginBtn.isEnabled = true

                status.text = if (reply.status == 200) {
                    "✓ Влязохте. Отворете приложението на часовника."
                } else {
                    "✗ Неуспешен вход: " +
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

package bg.shkolo.common

/**
 * Wire contract between the watch and the phone.
 *
 * The watch never talks to Shkolo. It sends a path, the phone executes it
 * (WebView or cookie-fetch) and replies with JSON shaped exactly like the
 * Worker's endpoints, so the UI code is a direct port of shkolo-ui.
 */
object Protocol {
    /** Wear MessageClient paths. */
    const val PATH_REQUEST = "/shkolo/request"
    const val PATH_RESPONSE = "/shkolo/response"
    const val PATH_STATUS = "/shkolo/status"

    /** Request keys. */
    const val KEY_ID = "id"           // request correlation id
    const val KEY_PATH = "path"       // e.g. "/grades", "/schedule", "/raw/diary"
    const val KEY_QUERY = "query"     // raw query string, may be empty

    /** Response keys. */
    const val KEY_STATUS = "status"   // HTTP-ish status the phone produced
    const val KEY_BODY = "body"       // JSON or raw text
    const val KEY_ERROR = "error"

    /** Status values the watch shows in the header chip. */
    object State {
        const val OK = 200
        const val NOT_LOGGED_IN = 401
        const val PHONE_UNREACHABLE = 503
        const val FAILED = 500
    }

    /** Endpoints the watch is allowed to ask for (least privilege). */
    val ALLOWED_PATHS = setOf(
        "/api/login",
        "/api/logout",
        "/api/session",
        "/grades",
        "/grades/subjects",
        "/schedule",
        "/raw/diary",
        "/raw/absences",
        "/raw/homeworks",
    )

    fun isAllowed(path: String): Boolean =
        path in ALLOWED_PATHS || path.startsWith("/raw/")
}

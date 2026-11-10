package app.unscroll.sync

import org.json.JSONException
import org.json.JSONObject

/** Crée un compte anonyme. Le corps de la réponse ne doit pas être journalisé : il contient le jeton. */
internal object SyncAccount {
    fun create(baseUrl: String): ProvisionResult {
        val url = SyncRules.accountUrl(baseUrl) ?: return ProvisionResult(ok = false, reason = "invalid")
        val reply = try {
            SyncHttp().postAnonymous(url)
        } catch (stop: SyncStop) {
            return ProvisionResult(ok = false, reason = stop.reason)
        } catch (_: Exception) {
            return ProvisionResult(ok = false, reason = "network")
        }
        if (reply.status !in 200..299) return ProvisionResult(ok = false, reason = "http-${reply.status}")
        val token = try {
            val json = JSONObject(reply.body)
            json.opt("token") as? String
        } catch (_: JSONException) {
            null
        }
        if (token == null || !SyncRules.validToken(token)) return ProvisionResult(ok = false, reason = "schema")
        return ProvisionResult(ok = true, token = token)
    }
}

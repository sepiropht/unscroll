package app.unscroll.sync

import java.net.URI
import org.json.JSONException
import org.json.JSONObject

/** Hosted plan. `enabled = false` on self-hosted servers: the app then shows no plan. */
data class BillingPlan(
    val enabled: Boolean = false,
    val state: String = "",
    val daysLeft: Int = 0,
    val canManage: Boolean = false,
    val cancelAtPeriodEnd: Boolean = false,
)

/**
 * Only the bearer token goes to the sync server here. No usage data. The
 * Stripe page opens in the browser; the server never sees the device data.
 */
internal object SyncBilling {
    private val STATES = setOf("trial", "active", "past_due", "ended", "free")

    fun billingUrl(base: String, path: String = ""): String? {
        val root = SyncRules.normalizeBase(base) ?: return null
        return "$root/v1/billing$path"
    }

    fun parsePlan(body: String): BillingPlan {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return BillingPlan()
        }
        if (json.optBoolean("enabled", false) != true) return BillingPlan()
        val state = json.optString("state", "")
        if (state !in STATES) return BillingPlan()
        return BillingPlan(
            enabled = true,
            state = state,
            daysLeft = json.optInt("daysLeft", 0).coerceAtLeast(0),
            canManage = json.optBoolean("canManage", false),
            cancelAtPeriodEnd = json.optBoolean("cancelAtPeriodEnd", false),
        )
    }

    /** Only https pages on stripe.com may be opened from a server answer. */
    fun safeStripeUrl(value: String?): String? {
        if (value == null || value.length > 4000 || value.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return null
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme?.lowercase() != "https" || !uri.rawUserInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "stripe.com" && !host.endsWith(".stripe.com")) return null
        return value
    }

    fun fetch(base: String, token: String): BillingPlan {
        if (!SyncRules.validToken(token)) return BillingPlan()
        val url = billingUrl(base) ?: return BillingPlan()
        return try {
            val reply = SyncHttp().exchange("GET", url, token, body = null, readBody = true, maxBytes = 16_384)
            if (reply.status != 200) BillingPlan() else parsePlan(reply.body)
        } catch (_: Exception) {
            BillingPlan()
        }
    }

    /** Returns the Stripe page to open, or a reason code. */
    fun link(base: String, token: String, portal: Boolean, yearly: Boolean): Pair<String?, String?> {
        if (!SyncRules.validToken(token)) return null to "invalid"
        val url = billingUrl(base, if (portal) "/portal" else "/checkout") ?: return null to "invalid"
        val body = if (portal) ByteArray(0) else JSONObject().put("plan", if (yearly) "yearly" else "monthly").toString().toByteArray()
        val reply = try {
            SyncHttp().exchange("POST", url, token, body = body, readBody = true, maxBytes = 16_384)
        } catch (stop: SyncStop) {
            return null to stop.reason
        } catch (_: Exception) {
            return null to "network"
        }
        if (reply.status != 200) return null to "http-${reply.status}"
        val target = try {
            safeStripeUrl(JSONObject(reply.body).optString("url", ""))
        } catch (_: JSONException) {
            null
        }
        return if (target == null) null to "schema" else target to null
    }
}

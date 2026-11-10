package app.unscroll.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SyncBillingTest {
    @Test
    fun parsesPlanAndHidesUnknown() {
        assertFalse(SyncBilling.parsePlan("""{"enabled":false}""").enabled)
        assertFalse(SyncBilling.parsePlan("not json").enabled)
        assertFalse(SyncBilling.parsePlan("""{"enabled":true,"state":"weird"}""").enabled)
        val plan = SyncBilling.parsePlan("""{"enabled":true,"state":"trial","daysLeft":9,"canManage":false}""")
        assertEquals(BillingPlan(enabled = true, state = "trial", daysLeft = 9), plan)
        assertEquals("ended", SyncBilling.parsePlan("""{"enabled":true,"state":"ended","daysLeft":0}""").state)
    }

    @Test
    fun buildsBillingUrlsUnderSyncRoot() {
        assertEquals("https://sync.example.com/v1/billing", SyncBilling.billingUrl("https://sync.example.com/"))
        assertEquals("https://sync.example.com/box/v1/billing/checkout", SyncBilling.billingUrl("https://sync.example.com/box/v1/blobs", "/checkout"))
        assertNull(SyncBilling.billingUrl("ftp://x"))
    }

    @Test
    fun opensOnlyStripeHttpsPages() {
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1#f", SyncBilling.safeStripeUrl("https://checkout.stripe.com/c/pay/cs_test_1#f"))
        assertEquals("https://billing.stripe.com/p/session/x", SyncBilling.safeStripeUrl("https://billing.stripe.com/p/session/x"))
        assertNull(SyncBilling.safeStripeUrl("http://checkout.stripe.com/x"))
        assertNull(SyncBilling.safeStripeUrl("https://stripe.com.evil.test/x"))
        assertNull(SyncBilling.safeStripeUrl("intent://x#Intent;end"))
    }
}

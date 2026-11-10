import { describe, expect, it } from 'vitest'
import { billingEndpoint, parsePlan, safeStripeUrl } from './billing'

describe('hosted billing', () => {
  it('parses a plan and hides anything unknown', () => {
    expect(parsePlan({ enabled: false })).toEqual({ enabled: false })
    expect(parsePlan({ enabled: true, state: 'nope' })).toEqual({ enabled: false })
    expect(parsePlan({ enabled: true, state: 'trial', daysLeft: 12.2, canManage: false })).toEqual({
      enabled: true,
      state: 'trial',
      daysLeft: 12,
      canManage: false,
      cancelAtPeriodEnd: false
    })
  })

  it('builds endpoints under the sync root', () => {
    expect(billingEndpoint('https://sync.example.com/', '')).toBe('https://sync.example.com/v1/billing')
    expect(billingEndpoint('https://sync.example.com/box/v1/blobs', '/checkout')).toBe('https://sync.example.com/box/v1/billing/checkout')
    expect(billingEndpoint('ftp://x', '')).toBeNull()
  })

  it('opens only https stripe.com pages', () => {
    expect(safeStripeUrl('https://checkout.stripe.com/c/pay/cs_test_1#frag')).toBe('https://checkout.stripe.com/c/pay/cs_test_1#frag')
    expect(safeStripeUrl('https://billing.stripe.com/p/session/x')).not.toBeNull()
    expect(safeStripeUrl('http://checkout.stripe.com/x')).toBeNull()
    expect(safeStripeUrl('https://stripe.com.evil.test/')).toBeNull()
    expect(safeStripeUrl('file:///etc/passwd')).toBeNull()
  })
})

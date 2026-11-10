import { afterEach, describe, expect, it, vi } from 'vitest';
import { billingLink, fetchPlan, parsePlan, safeBillingUrl } from '../src/edition/billing.ts';

afterEach(() => vi.unstubAllGlobals());

describe('billing', () => {
  it('parses plans and treats anything else as disabled', () => {
    expect(parsePlan({ enabled: false })).toEqual({ enabled: false });
    expect(parsePlan(null)).toEqual({ enabled: false });
    expect(parsePlan({ enabled: true, state: 'weird' })).toEqual({ enabled: false });
    expect(parsePlan({ enabled: true, state: 'trial', daysLeft: 9, canManage: false })).toMatchObject({ enabled: true, state: 'trial', daysLeft: 9 });
    expect(parsePlan({ enabled: true, state: 'ended', dataKeptUntil: '2026-12-01T00:00:00Z' }).dataKeptUntil).toBe('2026-12-01T00:00:00Z');
  });

  it('opens only https Stripe pages', () => {
    expect(safeBillingUrl('https://checkout.stripe.com/c/pay/cs_test_1')).toBe('https://checkout.stripe.com/c/pay/cs_test_1');
    expect(safeBillingUrl('https://billing.stripe.com/p/session/x')).toBeTruthy();
    expect(safeBillingUrl('http://checkout.stripe.com/x')).toBeNull();
    expect(safeBillingUrl('https://stripe.com.evil.test/x')).toBeNull();
    expect(safeBillingUrl('javascript:alert(1)')).toBeNull();
  });

  it('reports disabled on self-hosted or old servers', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('not found', { status: 404 })));
    expect(await fetchPlan('https://sync.example.com', 'token-123456')).toEqual({ enabled: false });
  });

  it('asks the server for a checkout link with the bearer token only', async () => {
    const spy = vi.fn(async (_url: string, _init: RequestInit) =>
      new Response(JSON.stringify({ url: 'https://checkout.stripe.com/c/pay/cs_test_1' }), { status: 200 }),
    );
    vi.stubGlobal('fetch', spy);
    const out = await billingLink('https://sync.example.com/', 'token-123456', 'checkout', 'yearly');
    expect(out).toEqual({ ok: true, url: 'https://checkout.stripe.com/c/pay/cs_test_1' });
    const [url, init] = spy.mock.calls[0];
    expect(url).toBe('https://sync.example.com/v1/billing/checkout');
    expect(init.body).toBe('{"plan":"yearly"}');
    expect((init.headers as Record<string, string>).authorization).toBe('Bearer token-123456');
  });
});

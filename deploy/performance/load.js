import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.FIXTURE));
const users = new SharedArray('synthetic users', () => fixture.users);
const mode = __ENV.MODE || 'shop';
const batch = mode === 'cold' || mode === 'burst' || mode === 'logical';
const ids = mode === 'cold' ? fixture.coldShops.slice(Number(__ENV.COLD_OFFSET || 0), Number(__ENV.COLD_OFFSET || 0) + 100) : fixture.hotShops;
const offered = Number(__ENV.RATE || 100);
export const options = {
  scenarios: {
    measured: batch
      ? { executor: 'shared-iterations', vus: Number(__ENV.VUS || 10), iterations: Number(__ENV.ITERATIONS || 100), maxDuration: '60s' }
      : { executor: 'constant-arrival-rate', rate: offered, timeUnit: '1s', duration: __ENV.DURATION || '30s', preAllocatedVUs: 60, maxVUs: 120, gracefulStop: '15s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  discardResponseBodies: false,
  thresholds: { http_req_failed: ['rate<=0.001'], business_unexpected: ['count==0'], content_valid: ['rate>=0.999'], dropped_iterations: ['count==0'] },
};
const accepted = new Counter('business_accepted');
const rejected = new Counter('business_rejected');
const unexpected = new Counter('business_unexpected');
const valid = new Rate('content_valid');
const shared = new Counter('cache_shared');
const redis = new Counter('cache_redis');
const backend = new Counter('cache_backend');
const requests = new Counter('business_requests');

export default function () {
  const i = exec.scenario.iterationInTest;
  const isOrder = mode === 'order' || mode === 'duplicate';
  let id;
  if (mode === 'mixed') id = i % 10 < 8 ? fixture.hotShops[i % 4] : fixture.otherShops[i % fixture.otherShops.length];
  else if (mode === 'cold') id = ids[i];
  else if (mode === 'burst' || mode === 'logical') id = __ENV.SHOP_ID;
  else id = fixture.hotShops[i % fixture.hotShops.length];
  const path = isOrder ? `/voucher-order/seckill/${__ENV.VOUCHER_ID}` : (__ENV.EDGE === 'true' ? `/api/shop/${id}` : `/shop/${id}`);
  const params = { timeout: __ENV.TIMEOUT || '5s', tags: { name: isOrder ? 'seckill' : 'shop-detail' } };
  if (isOrder) params.headers = { authorization: users[mode === 'duplicate' ? 0 : i].token };
  const startedAt = isOrder ? Date.now() : 0;
  const r = isOrder ? http.post(__ENV.BASE + path, null, params) : http.get(__ENV.BASE + path, params);
  requests.add(1);
  let data;
  try { data = r.json(); } catch (_) { data = null; }
  if (isOrder) {
    const ok = r.status === 200 && data && data.success === true && typeof data.data === 'string';
    const refusal = r.status === 200 && data && data.success === false && /库存不足|重复下单/.test(data.errorMsg || '');
    accepted.add(ok ? 1 : 0); rejected.add(refusal ? 1 : 0); unexpected.add(ok || refusal ? 0 : 1); valid.add(ok || refusal);
    if (ok) console.log(JSON.stringify({ kind: 'accepted-order', orderId: data.data, userIndex: mode === 'duplicate' ? 0 : i, startedHostEpochMs: startedAt, responseHostEpochMs: Date.now() }));
  } else {
    const ok = r.status === 200 && data && data.success === true && String(data.data?.id) === String(id);
    valid.add(ok); unexpected.add(ok ? 0 : 1);
    const hit = r.headers['X-Hmdp-Cache'] || r.headers['X-HMDP-Cache'] || '';
    shared.add(hit === 'shared' ? 1 : 0); redis.add(hit === 'redis' ? 1 : 0); backend.add(hit === 'backend' ? 1 : 0);
  }
}

export function handleSummary(data) {
  data.test = { mode, base: __ENV.BASE, targetRate: batch ? null : offered, duration: __ENV.DURATION || '30s', voucherId: __ENV.VOUCHER_ID || null, timestamp: new Date().toISOString() };
  return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) };
}

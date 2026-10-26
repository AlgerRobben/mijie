const fs = require('fs');
const vm = require('vm');
const assert = require('assert/strict');
const html = fs.readFileSync('D:/JavaCode/nginx-1.18.0-hmdp/html/hmdp/shop-detail.html', 'utf8');
const script = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const id = '650000000000000001';
const settle = async () => { for(let i=0;i<5;i++) await new Promise(setImmediate); };

function fixture(state, queryError = false) {
  let config;
  const messages = {success: [], error: []}, saved = {}, timers = [];
  const context = {
    Vue: function(options) { config = options; }, util: {}, token: 'test-token',
    sessionStorage: {getItem: key => saved[key] || null, setItem: (key, value) => saved[key] = value},
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout: () => {},
    axios: {
      post: () => Promise.resolve({data: id}),
      get: url => url.includes('/status/') ? (queryError ? Promise.reject('network error')
        : Promise.resolve({data: {orderId: id, state, message: 'result-' + state}})) : Promise.resolve({data: []})
    }
  };
  vm.runInNewContext(script, context);
  const app = {...config.data, shop: {id: 1},
    $set: (object, key, value) => object[key] = value, $delete: (object, key) => delete object[key],
    $message: {success: text => messages.success.push(text), error: text => messages.error.push(text)}};
  for(const [name, method] of Object.entries(config.methods)) app[name] = method.bind(app);
  return {app, messages, timers, saved};
}

(async () => {
  const voucher = {id: 42, stock: 1, beginTime: new Date(Date.now()-60000), endTime: new Date(Date.now()+60000)};
  const pending = fixture('PENDING'); pending.app.seckill(voucher); await settle();
  assert.equal(pending.messages.success.length, 0);
  assert.equal(pending.app.pendingOrders[42], id); assert.equal(pending.timers.length, 1);
  const success = fixture('SUCCEEDED'); success.app.seckill(voucher); await settle();
  assert.equal(success.messages.success.length, 1); assert.equal(success.app.pendingOrders[42], undefined);
  const failed = fixture('FAILED'); failed.app.seckill(voucher); await settle();
  assert.equal(failed.messages.error.length, 1); assert.equal(failed.app.pendingOrders[42], undefined);
  const unavailable = fixture('PENDING', true); unavailable.app.seckill(voucher); await settle();
  assert.equal(unavailable.app.pendingOrders[42], id); assert.equal(unavailable.messages.error.length, 0);
  const waiting = fixture('RETRYING'); waiting.app.pendingOrders[42] = id; waiting.app.pollOrder(42, id, 59); await settle();
  assert.equal(waiting.app.pendingOrders[42], id); assert.equal(waiting.timers.length, 0);
  assert.equal(waiting.messages.success.length, 0); assert.equal(waiting.messages.error.length, 0);
  console.log('PASS 5 frontend result-flow checks: accepted, success, compensated failure, unavailable query, long processing');
})().catch(error => { console.error(error); process.exitCode = 1; });

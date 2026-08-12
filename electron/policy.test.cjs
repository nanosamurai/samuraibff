const assert = require('node:assert/strict');
const test = require('node:test');

const policy = require('./policy.cjs');

test('resolveBackendOrigin uses canonical environment precedence', () => {
  assert.equal(policy.resolveBackendOrigin({}), 'http://localhost:8000');
  assert.equal(
    policy.resolveBackendOrigin({ SAMURAIBFF_ELECTRON_DEV_URL: 'http://127.0.0.1:9000/' }),
    'http://127.0.0.1:9000',
  );
  assert.equal(
    policy.resolveBackendOrigin({
      NANOSAMURAI_API_URL: 'https://app.example/',
      SAMURAIBFF_ELECTRON_DEV_URL: 'http://localhost:9000',
    }),
    'https://app.example',
  );
});

test('normalizeBackendOrigin permits HTTPS and loopback HTTP origins', () => {
  assert.equal(policy.normalizeBackendOrigin('https://app.example:8443/'), 'https://app.example:8443');
  assert.equal(policy.normalizeBackendOrigin('http://localhost:8000'), 'http://localhost:8000');
  assert.equal(policy.normalizeBackendOrigin('http://127.0.0.1:8000'), 'http://127.0.0.1:8000');
  assert.equal(policy.normalizeBackendOrigin('http://[::1]:8000'), 'http://[::1]:8000');
});

test('normalizeBackendOrigin rejects unsafe or ambiguous values', () => {
  const invalidValues = [
    '',
    'app.example',
    'ftp://app.example',
    'http://app.example',
    'https://user:password@app.example',
    'https://app.example/api',
    'https://app.example/?tenant=x',
    'https://app.example/#fragment',
  ];

  for (const value of invalidValues) {
    assert.throws(() => policy.normalizeBackendOrigin(value));
  }
});

test('normalizeNextPath accepts only internal pathnames', () => {
  const origin = 'https://app.example';
  assert.equal(policy.normalizeNextPath('/recordings/abc', origin), '/recordings/abc');
  assert.equal(policy.normalizeNextPath(null, origin), '/recordings');

  for (const value of ['https://evil.example', '//evil.example', '/\\evil', '/live?x=1', '/live#x']) {
    assert.throws(() => policy.normalizeNextPath(value, origin));
  }
});

test('main navigation and IPC senders are pinned to the BFF origin', () => {
  const origin = 'https://app.example';
  assert.equal(policy.isAllowedMainNavigation('https://app.example/live', origin), true);
  assert.equal(policy.isAuthorizedSender('https://app.example/recordings', origin), true);
  assert.equal(policy.isAllowedMainNavigation('https://idp.example/login', origin), false);
  assert.equal(policy.isAuthorizedSender('file:///tmp/index.html', origin), false);
  assert.equal(policy.isAuthorizedSender('not a URL', origin), false);
});

test('authentication navigation permits secure providers and loopback development', () => {
  const origin = 'https://app.example';
  assert.equal(policy.isAllowedAuthNavigation('https://idp.example/login', origin), true);
  assert.equal(policy.isAllowedAuthNavigation('http://localhost:8080/login', origin), true);
  assert.equal(policy.isAllowedAuthNavigation('http://idp.example/login', origin), false);
  assert.equal(policy.isAllowedAuthNavigation('file:///tmp/login.html', origin), false);
  assert.equal(policy.isAllowedAuthNavigation('https://user:pass@idp.example/login', origin), false);
});

test('OIDC completion requires the exact BFF route', () => {
  const origin = 'https://app.example';
  assert.equal(policy.isAuthCompletion('https://app.example/live', origin, '/live'), true);
  assert.equal(policy.isAuthCompletion('https://app.example/auth/callback', origin, '/live'), false);
  assert.equal(policy.isAuthCompletion('https://idp.example/live', origin, '/live'), false);
});

test('OIDC flow state settles exactly once for completion or cancellation', () => {
  const completed = policy.createAuthFlowState();
  assert.equal(completed.status(), 'pending');
  assert.equal(completed.complete(), true);
  assert.equal(completed.status(), 'completed');
  assert.equal(completed.cancel(), false);
  assert.equal(completed.fail(), false);

  const cancelled = policy.createAuthFlowState();
  assert.equal(cancelled.cancel(), true);
  assert.equal(cancelled.status(), 'cancelled');
  assert.equal(cancelled.complete(), false);
});

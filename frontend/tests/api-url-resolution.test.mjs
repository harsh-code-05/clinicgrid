import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveApiBaseUrl, resolveOAuthLoginUrl, createApiClient } from '../src/api/axios.js';

test('resolveApiBaseUrl defaults to /api when input is missing or empty', () => {
  assert.equal(resolveApiBaseUrl(), '/api');
  assert.equal(resolveApiBaseUrl(undefined), '/api');
  assert.equal(resolveApiBaseUrl(null), '/api');
  assert.equal(resolveApiBaseUrl(''), '/api');
  assert.equal(resolveApiBaseUrl('   '), '/api');
  assert.equal(resolveApiBaseUrl('/'), '/api');
  assert.equal(resolveApiBaseUrl('///'), '/api');
  assert.equal(resolveApiBaseUrl(123), '/api');
});

test('resolveApiBaseUrl trims whitespace and trailing slashes correctly', () => {
  assert.equal(resolveApiBaseUrl('/api/'), '/api');
  assert.equal(resolveApiBaseUrl('/api///'), '/api');
  assert.equal(resolveApiBaseUrl('http://localhost:8080/api/'), 'http://localhost:8080/api');
  assert.equal(resolveApiBaseUrl('https://hms-api.example.com/'), 'https://hms-api.example.com');
  assert.equal(resolveApiBaseUrl('  https://hms-api.example.com/api/  '), 'https://hms-api.example.com/api');
});

test('resolveOAuthLoginUrl resolves relative path or split-origin backend origin', () => {
  assert.equal(resolveOAuthLoginUrl(), '/oauth2/authorization/google');
  assert.equal(resolveOAuthLoginUrl('/api'), '/oauth2/authorization/google');
  assert.equal(resolveOAuthLoginUrl('https://hms-api.onrender.com/api'), 'https://hms-api.onrender.com/oauth2/authorization/google');
  assert.equal(resolveOAuthLoginUrl('https://hms-api.onrender.com'), 'https://hms-api.onrender.com/oauth2/authorization/google');
  assert.equal(resolveOAuthLoginUrl('http://localhost:8080/api/'), 'http://localhost:8080/oauth2/authorization/google');
});

test('createApiClient preserves credentials, headers, and security options with resolved baseURL', () => {
  const customBaseUrl = 'https://api.hospital.example.com/api';
  const client = createApiClient(async (req) => ({ status: 200, data: req.baseURL }), customBaseUrl);

  assert.equal(client.defaults.baseURL, customBaseUrl);
  assert.equal(client.defaults.withCredentials, true);
  assert.equal(client.defaults.allowAbsoluteUrls, false);
  assert.equal(client.defaults.headers['Content-Type'], 'application/json');
});

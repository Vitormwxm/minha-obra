import assert from 'node:assert/strict';
import test from 'node:test';
import {
  newOperationKey, newTemporaryPassword, normalizedUsername, OperationKey,
  passwordProblem, validUsername,
} from '../src/app/security-input.ts';

test('password policy uses Unicode characters and the BCrypt UTF-8 limit', () => {
  assert.equal(passwordProblem('a'.repeat(12)), null);
  assert.equal(passwordProblem('a'.repeat(64)), null);
  assert.notEqual(passwordProblem('a'.repeat(11)), null);
  assert.notEqual(passwordProblem('a'.repeat(65)), null);
  assert.equal(passwordProblem('🔨'.repeat(18)), null);
  assert.notEqual(passwordProblem('🔨'.repeat(19)), null);
  assert.notEqual(passwordProblem('🔨'.repeat(6)), null);
  assert.equal(passwordProblem('a'.repeat(59) + '🔨'.repeat(3)), null);
});

test('passwords can contain spaces but cannot be blank or contain invalid Unicode', () => {
  assert.equal(passwordProblem('  minha senha particular  '), null);
  assert.notEqual(passwordProblem(' '.repeat(12)), null);
  assert.notEqual(passwordProblem('a'.repeat(12) + '\ud800'), null);
});

test('usernames normalize without permitting paths or ambiguous punctuation prefixes', () => {
  assert.equal(normalizedUsername('  Maria.Silva  '), 'maria.silva');
  for (const value of ['abc', 'a'.repeat(40), 'maria.silva', 'pessoa_2', 'obra-1']) {
    assert.equal(validUsername(value), true, value);
  }
  for (const value of ['ab', 'a'.repeat(41), '../user', '_user', 'maria silva', 'josé', 'maria@site.com']) {
    assert.equal(validUsername(value), false, value);
  }
});

test('generated temporary passwords satisfy the server policy', () => {
  const password = newTemporaryPassword();
  assert.equal(password.length, 20);
  assert.match(password, /^[A-Za-z0-9_-]+$/);
  assert.equal(passwordProblem(password), null);
});

test('operation keys are UUID v4 without depending on crypto.randomUUID', () => {
  assert.match(newOperationKey(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('a network retry reuses a key while another payload or version gets a new key', async () => {
  const operation = new OperationKey();
  const first = await operation.forPayload('reset:account-1', { version: 1, password: 'test-password-one' });
  assert.equal(await operation.forPayload('reset:account-1', { version: 1, password: 'test-password-one' }), first);
  const changed = await operation.forPayload('reset:account-1', { version: 1, password: 'test-password-two' });
  assert.notEqual(changed, first);
  const newVersion = await operation.forPayload('reset:account-1', { version: 2, password: 'test-password-two' });
  assert.notEqual(newVersion, changed);
  assert.notEqual(await operation.forPayload('reset:account-2', { version: 2, password: 'test-password-two' }), newVersion);
});

test('a completed operation clears the retry identity', async () => {
  const operation = new OperationKey();
  const before = await operation.forPayload('create', { username: 'test-person' });
  operation.clear();
  assert.notEqual(await operation.forPayload('create', { username: 'test-person' }), before);
});

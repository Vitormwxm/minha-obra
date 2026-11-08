import assert from 'node:assert/strict';
import test from 'node:test';
import {
  newOperationKey, newTemporaryPassword, normalizedUsername, OperationKey,
  passwordProblem, validUsername,
} from '../src/app/security-input.ts';

test('new passwords require eight ASCII digits and preserve leading zeroes', () => {
  assert.equal(passwordProblem('01234567'), null);
  assert.equal(passwordProblem('00000000'), null);
  for (const value of ['', '1234567', '123456789', '1234567x', ' 01234567', '01234567\n', '１２３４５６７８']) {
    assert.notEqual(passwordProblem(value), null);
  }
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
  assert.equal(password.length, 8);
  assert.match(password, /^[0-9]{8}$/);
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

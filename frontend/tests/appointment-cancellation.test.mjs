import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import {
  mutationErrorMessage,
  requireAppointmentResult,
  requireAppointmentsList,
} from '../src/api/responseValidation.js';
import * as cancellationHelpers from '../src/utils/appointmentCancellation.js';

const json = (data) => ({ status: 200, headers: { 'content-type': 'application/json' }, data });
const html = { status: 200, headers: { 'content-type': 'text/html' }, data: '<html>Login</html>' };

function loadHandler(component, name, bindings) {
  const source = readFileSync(new URL(component, import.meta.url), 'utf8');
  const code = source.match(new RegExp(`const ${name} = (async [\\s\\S]+?\\n  \\});`))?.[1];
  assert.ok(code, `Missing ${name} in ${component}`);
  return vm.runInNewContext(`(${code})`, bindings);
}

test('cancellation helper functions normalize Unicode whitespace, format notices, and detect 409 refresh', () => {
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload(null), { reason: null });
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload('   \n\t  '), { reason: null });
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload('\u2003'), { reason: null });
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload('\u00A0\u202F\uFEFF\u0085'), { reason: null });
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload('\u2003Reschedule\u2003'), {
    reason: 'Reschedule',
  });
  assert.deepEqual(cancellationHelpers.buildCancelRequestPayload('  Patient unavailable  '), {
    reason: 'Patient unavailable',
  });

  assert.equal(cancellationHelpers.validateCancellationReason(''), '');
  assert.equal(
    cancellationHelpers.validateCancellationReason('a'.repeat(cancellationHelpers.MAX_CANCELLATION_REASON_LENGTH)),
    '',
  );
  assert.equal(
    cancellationHelpers.validateCancellationReason(`\u2003${'a'.repeat(500)}\u2003`),
    '',
  );
  assert.equal(
    cancellationHelpers.validateCancellationReason('Reschedule\u0000visit'),
    'Cancellation reason cannot contain NUL characters.',
  );
  assert.match(
    cancellationHelpers.validateCancellationReason('a'.repeat(cancellationHelpers.MAX_CANCELLATION_REASON_LENGTH + 1)),
    /cannot exceed 500 characters/,
  );

  assert.match(
    cancellationHelpers.formatCancellationNotice({ id: 5, cancellationSlotOutcome: 'RELEASED' }),
    /slot was released/,
  );
  assert.match(
    cancellationHelpers.formatCancellationNotice({ id: 5, cancellationSlotOutcome: 'RETAINED_UNAVAILABLE' }),
    /older schedule revision/,
  );
  assert.match(
    cancellationHelpers.formatCancellationNotice({ id: 5, cancellationSlotOutcome: 'WITHHELD' }),
    /withheld/,
  );
  assert.match(
    cancellationHelpers.formatCancellationNotice({ id: 5, cancellationSlotOutcome: 'OVERLAP' }),
    /overlaps/,
  );
  assert.equal(
    cancellationHelpers.formatCancelledByRole('ADMIN'),
    'Administrator',
  );
  assert.equal(cancellationHelpers.formatCancelledByRole('UNKNOWN'), '');
  assert.equal(cancellationHelpers.formatSlotOutcomeLabel('RETAINED_UNAVAILABLE'), 'Slot kept withdrawn (schedule updated)');
  assert.equal(cancellationHelpers.formatCancelledAt('invalid'), '');
  assert.notEqual(cancellationHelpers.formatCancelledAt('2099-05-01T09:30:00Z'), '');
  assert.equal(cancellationHelpers.shouldRefreshAfterCancelError({ response: { status: 409 } }), true);
  assert.equal(cancellationHelpers.shouldRefreshAfterCancelError({ response: { status: 403 } }), false);
});

for (const portal of [
  { name: 'PatientDashboard', path: '../src/pages/PatientDashboard.jsx', hasRefreshSlots: true },
  { name: 'DoctorDashboard', path: '../src/pages/DoctorDashboard.jsx', hasRefreshSlots: false },
  { name: 'AdminDashboard', path: '../src/pages/AdminDashboard.jsx', hasRefreshSlots: false },
]) {
  test(`${portal.name} handleConfirmCancel enforces lock, Unicode whitespace, and separate refresh warnings`, async () => {
    const patches = [];
    let pendingPatch;
    let cancellingAppt = { id: 42, status: 'CONFIRMED' };
    let cancelReason = '\u2003\t\u00A0';
    let cancelError = '';
    let globalNotice = '';
    let refreshWarning = '';
    let cancelRefreshWarning = '';
    let busy = false;
    let fetchCalls = 0;
    let slotRefreshes = 0;
    let fetchResult = false;
    const cancelInProgressRef = { current: false };

    const bindings = {
      ...cancellationHelpers,
      cancellingAppt,
      cancelReason,
      cancelInProgressRef,
      mountedRef: { current: true },
      activeUserIdRef: { current: 10 },
      requireAppointmentResult,
      mutationErrorMessage,
      setIsCancelling: (v) => { busy = v; },
      setCancelError: (v) => { cancelError = v; },
      setCancellingAppt: (v) => { cancellingAppt = v; },
      setCancelReason: (v) => { cancelReason = v; },
      setGlobalNotice: (v) => { globalNotice = v; },
      setRefreshWarning: (v) => { refreshWarning = v; },
      setCancelRefreshWarning: (v) => { cancelRefreshWarning = v; },
      fetchAppointments: async () => {
        fetchCalls += 1;
        return fetchResult;
      },
      api: {
        patch: (url, body) => new Promise((resolve) => {
          patches.push({ url, body });
          pendingPatch = resolve;
        }),
      },
    };
    if (portal.hasRefreshSlots) {
      bindings.refreshSlots = () => { slotRefreshes += 1; };
    }

    const cancel = loadHandler(portal.path, 'handleConfirmCancel', bindings);

    // 1. PATCH 200 -> GET fails produces separate globalNotice (clean) and distinct refreshWarning
    const first = cancel();
    await cancel();
    assert.equal(patches.length, 1);
    assert.deepEqual(patches[0], {
      url: '/appointments/42/cancel',
      body: { reason: null },
    });
    assert.equal(busy, true);

    pendingPatch(json({
      id: 42,
      status: 'CANCELLED',
      cancellationReason: null,
      cancelledAt: '2099-05-01T09:30:00Z',
      cancelledByRole: 'PATIENT',
      cancellationSlotOutcome: 'RETAINED_UNAVAILABLE',
    }));
    await first;

    assert.equal(cancelInProgressRef.current, false);
    assert.equal(busy, false);
    assert.equal(cancellingAppt, null);
    assert.equal(cancelReason, '');
    assert.equal(cancelError, '');
    assert.equal(fetchCalls, 1);
    if (portal.hasRefreshSlots) {
      assert.equal(slotRefreshes, 1);
    }
    assert.match(globalNotice, /older schedule revision/);
    assert.doesNotMatch(globalNotice, /stale|could not be refreshed/i);
    assert.match(refreshWarning, /could not be refreshed|stale/i);
    assert.equal(cancelRefreshWarning, '');

    // 2. PATCH 200 -> GET succeeds clears refreshWarning
    fetchCalls = 0;
    slotRefreshes = 0;
    fetchResult = true;
    cancellingAppt = { id: 42, status: 'CONFIRMED' };
    cancelReason = '\u2003Reschedule\u2003';
    const cleanSuccess = loadHandler(portal.path, 'handleConfirmCancel', {
      ...bindings,
      cancellingAppt,
      cancelReason,
      api: {
        patch: async (url, body) => {
          assert.equal(url, '/appointments/42/cancel');
          assert.deepEqual(body, { reason: 'Reschedule' });
          return json({
            id: 42,
            status: 'CANCELLED',
            cancellationReason: 'Reschedule',
            cancelledAt: '2099-05-01T09:30:00Z',
            cancelledByRole: 'PATIENT',
            cancellationSlotOutcome: 'RELEASED',
          });
        },
      },
    });
    await cleanSuccess();
    assert.equal(refreshWarning, '');
    assert.equal(cancelRefreshWarning, '');
    assert.match(globalNotice, /slot was released/);

    // 3. 501-char guard blocks PATCH
    const overLimit = loadHandler(portal.path, 'handleConfirmCancel', {
      ...bindings,
      cancellingAppt: { id: 42, status: 'CONFIRMED' },
      cancelReason: 'x'.repeat(501),
    });
    await overLimit();
    assert.equal(patches.length, 1);
    assert.match(cancelError, /cannot exceed 500 characters/);

    // 3b. NUL-character guard blocks PATCH
    const nulGuard = loadHandler(portal.path, 'handleConfirmCancel', {
      ...bindings,
      cancellingAppt: { id: 42, status: 'CONFIRMED' },
      cancelReason: 'Reschedule\u0000visit',
    });
    await nulGuard();
    assert.equal(patches.length, 1);
    assert.match(cancelError, /cannot contain NUL characters/);

    // 4. PATCH 409 -> GET fails preserves modal, preserves reason draft, sets cancelError AND visible cancelRefreshWarning
    fetchCalls = 0;
    slotRefreshes = 0;
    fetchResult = false;
    cancelError = '';
    refreshWarning = '';
    cancelRefreshWarning = '';
    cancellingAppt = { id: 42, status: 'CONFIRMED' };
    cancelReason = 'Running late';
    const conflictWithRefreshFailure = loadHandler(portal.path, 'handleConfirmCancel', {
      ...bindings,
      cancellingAppt,
      cancelReason,
      api: {
        patch: async () => {
          const err = new Error('Conflict');
          err.response = {
            status: 409,
            data: { code: 'APPOINTMENT_NOT_CANCELLABLE', message: 'Completed appointments cannot be cancelled.' },
          };
          throw err;
        },
      },
    });
    await conflictWithRefreshFailure();
    assert.equal(cancellingAppt?.id, 42);
    assert.equal(cancelReason, 'Running late');
    assert.match(cancelError, /Completed appointments cannot be cancelled/);
    assert.match(cancelRefreshWarning, /Unable to refresh|stale/i);
    assert.match(refreshWarning, /Unable to refresh|stale/i);
    assert.equal(fetchCalls, 1);
    if (portal.hasRefreshSlots) {
      assert.equal(slotRefreshes, 1);
    }

    // 5. HTML response fails safely without closing modal
    cancelError = '';
    cancelRefreshWarning = '';
    const htmlHandler = loadHandler(portal.path, 'handleConfirmCancel', {
      ...bindings,
      cancellingAppt: { id: 42, status: 'CONFIRMED' },
      cancelReason: 'Need reschedule',
      api: { patch: async () => html },
    });
    await htmlHandler();
    assert.match(cancelError, /unexpected response/);
  });
}

test('dashboards use requireAppointmentsList, expose zero cancelledByUserId fields, and render warning banners', async () => {
  for (const file of [
    '../src/pages/PatientDashboard.jsx',
    '../src/pages/DoctorDashboard.jsx',
    '../src/pages/AdminDashboard.jsx',
  ]) {
    const src = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(src, /cancelledByUserId/, `${file} must not reference cancelledByUserId`);
    assert.doesNotMatch(src, /type=["']checkbox["']/i, `${file} must not expose a slot-hold checkbox`);
    assert.match(src, /requireAppointmentsList/, `${file} must validate /appointments list response`);
    assert.match(src, /Cancellation Details/, `${file} should render Cancellation Details`);
    assert.match(src, /Cancelled before detailed tracking was enabled/, `${file} should render legacy fallback`);
    assert.match(src, /cancelRefreshWarning/, `${file} must render modal refresh warning`);
    assert.match(src, /refreshWarning/, `${file} must render page refresh warning`);

    // Ensure JSX still compiles cleanly via Vite's OXC transform
    const clean = src.replace(/^import .*\r?\n/gm, '').replace('export default function', 'function');
    const transformed = await transformWithOxc(clean, file, {
      jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' },
    });
    assert.ok(transformed.code.length > 0);
  }

  // Confirm requireAppointmentsList throws on malformed element metadata while supporting legacy null fields
  assert.throws(
    () => requireAppointmentsList(json([{ id: 7, status: 'CANCELLED', cancellationReason: {} }])),
    /unexpected response/,
  );
  assert.equal(
    requireAppointmentsList(json([{ id: 7, status: 'CANCELLED', cancelledAt: null }])).length,
    1,
  );
});

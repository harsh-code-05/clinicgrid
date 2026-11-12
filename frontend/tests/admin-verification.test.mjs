import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import {
  requireDoctorVerificationDetails,
  requireNoContentResponse,
  requirePreviewImageBlob,
  requireVerificationDocuments,
  requireVerificationQueue,
} from '../src/api/responseValidation.js';
import {
  DOCUMENT_KIND_CONFIG,
  VERIFICATION_STATUS_META,
  formatFileSize,
  formatVerificationError,
  formatVerificationTimestamp,
  parseApiErrorDetails,
} from '../src/utils/doctorVerification.js';
import { RequestSequencer, executeSequencedRequest } from '../src/utils/requestSequencer.js';

function componentSource(relPath, fnName) {
  return readFileSync(new URL(relPath, import.meta.url), 'utf8')
    .replace(/^import\s+[\s\S]*?\s+from\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace(/^import\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace(`export default function ${fnName}`, `function ${fnName}`);
}

const { code: panelCode } = await transformWithOxc(
  componentSource('../src/components/AdminVerificationPanel.jsx', 'AdminVerificationPanel'),
  'AdminVerificationPanel.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } },
);

const { code: dashboardCode } = await transformWithOxc(
  componentSource('../src/pages/AdminDashboard.jsx', 'AdminDashboard'),
  'AdminDashboard.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } },
);

async function flush(turns = 5) {
  for (let i = 0; i < turns; i += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function sameDeps(a, b) {
  return a?.length === b?.length && b.every((v, i) => Object.is(v, a[i]));
}

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return tree && typeof tree === 'object' ? [tree, ...nodes(tree.children)] : [];
}

function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('');
  return tree && typeof tree === 'object' ? text(tree.children) : String(tree ?? '');
}

function makeElement(type, props, ...children) {
  const flatProps = props ?? {};
  if (typeof type === 'function') {
    return type({ ...flatProps, children });
  }
  return { type, props: flatProps, children };
}

const JSON_HEADERS = { 'content-type': 'application/json' };
const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function makeQueueItem(doctorId, overrides = {}) {
  return {
    doctorId,
    doctorName: `Dr. Test ${doctorId}`,
    specialization: 'Cardiology',
    status: 'SUBMITTED',
    submittedAt: '2026-11-10T09:00:00',
    latestUpdatedAt: '2026-11-10T09:00:00',
    latestActorRole: 'DOCTOR',
    registrationNumber: `NMC-${doctorId}`,
    medicalCouncil: 'National Medical Commission',
    registrationYear: 2019,
    latestRejectionReason: null,
    ...overrides,
  };
}

function makeDetails(doctorId, overrides = {}) {
  return {
    doctorId,
    status: 'SUBMITTED',
    registrationNumber: `NMC-${doctorId}`,
    medicalCouncil: 'National Medical Commission',
    registrationYear: 2019,
    latestRejectionReason: null,
    reviewVersion: `rev-token-${doctorId}-v1`,
    history: [
      {
        previousStatus: 'UNVERIFIED',
        newStatus: 'SUBMITTED',
        actorRole: 'DOCTOR',
        occurredAt: '2026-11-10T09:00:00',
      },
    ],
    ...overrides,
  };
}

function makeDocs(doctorId) {
  return [
    {
      doctorId,
      documentKind: 'ID_PROOF',
      mediaType: 'image/png',
      fileSize: 1024,
      createdAt: '2026-11-10T08:50:00',
    },
    {
      doctorId,
      documentKind: 'QUALIFICATION',
      mediaType: 'image/jpeg',
      fileSize: 2048,
      createdAt: '2026-11-10T08:55:00',
    },
  ];
}

async function setupAdminPanel({ apiHandlers = {} } = {}) {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  const apiCalls = [];
  const createdUrls = [];
  const revokedUrls = [];
  let urlSeq = 0;

  const urlApi = {
    createObjectURL(blob) {
      const generated = `blob:hms-test/${++urlSeq}:${blob.type || 'unknown'}`;
      createdUrls.push(generated);
      return generated;
    },
    revokeObjectURL(url) {
      revokedUrls.push(url);
    },
  };

  const defaultApi = {
    get: async (url, config = {}) => {
      apiCalls.push({ method: 'GET', url, config });
      if (url === '/admin/doctor-verifications') {
        return {
          status: 200,
          headers: JSON_HEADERS,
          data: {
            items: [makeQueueItem(101), makeQueueItem(102, { status: 'UNDER_REVIEW' })],
            page: 0,
            size: 10,
            totalElements: 2,
            totalPages: 1,
            hasNext: false,
            hasPrevious: false,
          },
        };
      }
      const matchDetails = url.match(/^\/doctors\/(\d+)\/verification$/);
      if (matchDetails) {
        return { status: 200, headers: JSON_HEADERS, data: makeDetails(Number(matchDetails[1])) };
      }
      const matchDocs = url.match(/^\/doctors\/(\d+)\/verification\/documents$/);
      if (matchDocs) {
        return { status: 200, headers: JSON_HEADERS, data: makeDocs(Number(matchDocs[1])) };
      }
      throw new Error(`Unexpected GET ${url}`);
    },
    post: async (url, body, config = {}) => {
      apiCalls.push({ method: 'POST', url, body, config });
      return { status: 204, data: null };
    },
    ...apiHandlers,
  };

  const context = vm.createContext({
    h: makeElement,
    Fragment: Symbol('Fragment'),
    useState(initial) {
      const cell = hooks[cursor++] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [
        cell.value,
        (next) => {
          cell.value = typeof next === 'function' ? next(cell.value) : next;
        },
      ];
    },
    useRef(initial) {
      return hooks[cursor++] ??= { current: initial };
    },
    useCallback(fn, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) hooks[i] = { fn, deps };
      return hooks[i].fn;
    },
    useEffect(effect, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) {
        pendingEffects.push(() => {
          hooks[i]?.cleanup?.();
          hooks[i] = { deps, cleanup: effect() };
        });
      }
    },
    api: defaultApi,
    AbortController,
    URL: urlApi,
    RequestSequencer,
    executeSequencedRequest,
    requireVerificationQueue,
    requireDoctorVerificationDetails,
    requireNoContentResponse,
    requireVerificationDocuments,
    requirePreviewImageBlob,
    DOCUMENT_KIND_CONFIG,
    VERIFICATION_STATUS_META,
    formatFileSize,
    formatVerificationError,
    formatVerificationTimestamp,
    parseApiErrorDetails,
  });

  const script = new vm.Script(`${panelCode}; AdminVerificationPanel;`);
  const Component = script.runInContext(context);

  let latestTree;
  function renderSync() {
    cursor = 0;
    latestTree = Component();
    const effects = pendingEffects;
    pendingEffects = [];
    for (const run of effects) {
      run();
    }
    return latestTree;
  }

  async function settle(turns = 5) {
    renderSync();
    await flush(turns);
    return renderSync();
  }

  function unmount() {
    hooks.forEach((h) => h?.cleanup?.());
  }

  await settle();

  return { render: settle, unmount, getTree: () => latestTree, apiCalls, createdUrls, revokedUrls };
}

test('AdminDashboard renders Doctor Verification tab strictly separated from appointments and never calls doctor-applications', async () => {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  let renderedAdminPanel = false;
  const requestedUrls = [];

  const context = vm.createContext({
    h: makeElement,
    Fragment: Symbol('Fragment'),
    useState(initial) {
      const cell = hooks[cursor++] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [
        cell.value,
        (next) => {
          cell.value = typeof next === 'function' ? next(cell.value) : next;
        },
      ];
    },
    useRef(initial) {
      return hooks[cursor++] ??= { current: initial };
    },
    useCallback(fn, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) hooks[i] = { fn, deps };
      return hooks[i].fn;
    },
    useEffect(effect, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) {
        pendingEffects.push(() => {
          hooks[i]?.cleanup?.();
          hooks[i] = { deps, cleanup: effect() };
        });
      }
    },
    useAuth: () => ({
      user: { id: 1, email: 'admin@hms.test', role: 'ADMIN' },
      logout: async () => {},
      formDrafts: { get: () => null },
    }),
    useNavigate: () => () => {},
    api: {
      get: async (url) => {
        requestedUrls.push(url);
        return { data: [] };
      },
    },
    AvailabilityManager: () => ({ type: 'div', props: {}, children: [] }),
    AdminVerificationPanel: () => {
      renderedAdminPanel = true;
      return { type: 'section', props: { 'data-testid': 'admin-verif-panel' }, children: [] };
    },
    AppointmentListState: (props) => ({ type: 'div', props: {}, children: props.children }),
    AppointmentMetricsState: () => ({ type: 'div', props: {}, children: [] }),
    requireAppointmentResult: (r) => r.data,
    mutationErrorMessage: () => 'err',
    RequestSequencer,
    executeSequencedRequest,
  });

  const script = new vm.Script(`${dashboardCode}; AdminDashboard;`);
  const Dashboard = script.runInContext(context);
  const runRender = () => {
    cursor = 0;
    const tree = Dashboard();
    const effects = pendingEffects;
    pendingEffects = [];
    effects.forEach((fn) => fn());
    return tree;
  };
  const settleDash = async () => {
    runRender();
    await flush();
    return runRender();
  };

  let tree = await settleDash();
  const verifBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Doctor Verification'));
  assert.ok(verifBtn, 'Doctor Verification tab button must exist');
  verifBtn.props.onClick();

  tree = await settleDash();
  assert.equal(renderedAdminPanel, true);
  assert.equal(requestedUrls.some((u) => u.includes('doctor-applications')), false);
  hooks.forEach((h) => h?.cleanup?.());
});

test('queue filtering and pagination query /admin/doctor-verifications with status and page params', async () => {
  const queueCalls = [];
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          queueCalls.push(JSON.parse(JSON.stringify(config.params)));
          const page = config.params?.page ?? 0;
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(page === 0 ? 101 : 205, { status: config.params?.status || 'SUBMITTED' })],
              page,
              size: 10,
              totalElements: 12,
              totalPages: 2,
              hasNext: page === 0,
              hasPrevious: page > 0,
            },
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  assert.deepEqual(queueCalls[0], { page: 0, size: 10 });

  const rejectedBtn = nodes(tree).find((n) => n.type === 'button' && text(n) === 'Rejected');
  rejectedBtn.props.onClick();
  tree = await render();
  assert.deepEqual(queueCalls.at(-1), { page: 0, size: 10, status: 'REJECTED' });

  const nextBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Next Page'));
  assert.equal(nextBtn.props.disabled, false);
  nextBtn.props.onClick();
  await render();
  assert.deepEqual(queueCalls.at(-1), { page: 1, size: 10, status: 'REJECTED' });
  unmount();
});

test('switching doctors clears prior doctor details immediately when new detail request fails', async () => {
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101), makeQueueItem(102)],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { registrationNumber: 'SECRET-DOC-101' }) };
        }
        if (url === '/doctors/101/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
        }
        if (url === '/doctors/102/verification' || url === '/doctors/102/verification/documents') {
          const err = new Error('Server error');
          err.response = { status: 500, data: { message: 'Temporary failure loading Doctor #102' } };
          throw err;
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  const reviewButtons = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  await reviewButtons[0].props.onClick();
  tree = await render();
  assert.match(text(tree), /SECRET-DOC-101/);

  const secondBtn = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'))[0];
  await secondBtn.props.onClick();
  tree = await render();
  assert.doesNotMatch(text(tree), /SECRET-DOC-101/);
  assert.match(text(tree), /Temporary failure loading Doctor #102/);
  unmount();
});

test('start review unlocks private preview and strictly manages Blob URL creation and revocation', async () => {
  let docStatus = 'SUBMITTED';
  let previewMediaType = 'image/png';
  let previewPayload = new Blob([PNG_BYTES], { type: 'image/png' });
  const seenPreviewConfigs = [];

  const { render, unmount, createdUrls, revokedUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: docStatus }), makeQueueItem(102, { status: 'UNDER_REVIEW' })],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status: docStatus }) };
        }
        if (url === '/doctors/102/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(102, { status: 'UNDER_REVIEW' }) };
        }
        if (url === '/doctors/101/verification/documents' || url === '/doctors/102/verification/documents') {
          const did = url.includes('/101/') ? 101 : 102;
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(did) };
        }
        if (url.includes('/verification/documents/id-proof') || url.includes('/verification/documents/qualification')) {
          seenPreviewConfigs.push({
            url,
            responseType: config.responseType,
            params: config.params,
          });
          assert.equal(config.responseType, 'blob', 'Document preview must request binary blob responseType');
          assert.match(String(config.params?.reviewVersion), /^rev-token-\d+-v1$/);
          return {
            status: 200,
            headers: { 'content-type': previewMediaType },
            data: previewPayload,
          };
        }
        throw new Error(`Unexpected GET ${url}`);
      },
      post: async (url, body) => {
        if (url === '/doctors/101/verification/review') {
          assert.deepEqual(JSON.parse(JSON.stringify(body)), { reviewVersion: 'rev-token-101-v1' });
          docStatus = 'UNDER_REVIEW';
          return { status: 204, data: null };
        }
        throw new Error(`Unexpected POST ${url}`);
      },
    },
  });

  let tree = await render();
  await nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier')).props.onClick();
  tree = await render();

  // Preview must be disabled in SUBMITTED status
  const lockedPreviewBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof'));
  assert.equal(lockedPreviewBtn.props.disabled, true);

  // Start Review transitions to UNDER_REVIEW and enables preview
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Start Review')).props.onClick();
  tree = await render();
  assert.match(text(tree), /Preview enabled/);

  // Preview ID Proof
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 1);
  assert.ok(nodes(tree).find((n) => n.type === 'img' && n.props.src === createdUrls[0]));

  // Switching to Qualification preview revokes first Blob URL and creates second
  previewMediaType = 'image/jpeg';
  previewPayload = new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' });
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview Qualification')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 2);
  assert.deepEqual(revokedUrls, [createdUrls[0]]);

  // Reject HTML response returned with blob responseType without creating object URL
  previewMediaType = 'text/html';
  previewPayload = new Blob(['<html>login</html>'], { type: 'text/html' });
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 2);
  assert.equal(revokedUrls.length, 2);
  assert.match(text(tree), /unexpected response/);

  // Load clean preview then close via Close Preview button
  previewMediaType = 'image/png';
  previewPayload = new Blob([PNG_BYTES], { type: 'image/png' });
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 3);
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Close Preview').props.onClick();
  tree = await render();
  assert.equal(revokedUrls.at(-1), createdUrls[2]);

  // Open preview again then switch doctor -> revokes active URL
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 4);
  const reviewButtons = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  await reviewButtons.at(-1).props.onClick();
  tree = await render();
  assert.equal(revokedUrls.at(-1), createdUrls[3]);

  // Open preview on Doctor 102 and unmount -> revokes remaining active URL
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 5);
  assert.ok(seenPreviewConfigs.length >= 5);
  unmount();
  assert.equal(revokedUrls.at(-1), createdUrls[4]);
});

test('preview Blob 409 error decodes JSON error message and triggers state refresh', async () => {
  let docStatus = 'UNDER_REVIEW';
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: docStatus })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status: docStatus }) };
        }
        if (url === '/doctors/101/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
        }
        docStatus = 'VERIFIED';
        const err = new Error('Conflict');
        err.response = {
          status: 409,
          data: new Blob(
            [JSON.stringify({ code: 'STALE_REVIEW_VERSION', message: 'Verification state changed before preview. Refreshing details.' })],
            { type: 'application/json' },
          ),
        };
        throw err;
      },
    },
  });

  let tree = await render();
  await nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier')).props.onClick();
  tree = await render();

  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.match(text(tree), /Verification state changed before preview/);
  assert.match(text(tree), /Preview locked — available only in UNDER_REVIEW/);
  unmount();
});

test('Approve and Reject send reviewVersion, suppress duplicate clicks, and handle 409 STALE_REVIEW_VERSION', async () => {
  let version = 'rev-v1';
  let status = 'UNDER_REVIEW';
  let approveCalls = 0;
  let rejectCalls = 0;
  let forceStale = true;
  const approveGate = deferred();
  const postedActions = [];

  const { render, unmount, createdUrls, revokedUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status, reviewVersion: version }) };
        }
        if (url === '/doctors/101/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
        }
        return {
          status: 200,
          headers: { 'content-type': 'image/png' },
          data: new Blob([PNG_BYTES], { type: 'image/png' }),
        };
      },
      post: async (url, body) => {
        postedActions.push({ url, body: JSON.parse(JSON.stringify(body ?? null)) });
        if (url === '/doctors/101/verification/reject') {
          rejectCalls += 1;
          if (forceStale) {
            forceStale = false;
            version = 'rev-v2';
            const err = new Error('Stale');
            err.response = {
              status: 409,
              data: {
                code: 'STALE_REVIEW_VERSION',
                message: 'Doctor verification changed since you opened this dossier. Refreshing details.',
              },
            };
            throw err;
          }
          return { status: 204, data: null };
        }
        if (url === '/doctors/101/verification/approve') {
          approveCalls += 1;
          await approveGate.promise;
          status = 'VERIFIED';
          return { status: 204, data: null };
        }
        throw new Error(`Unexpected POST ${url}`);
      },
    },
  });

  let tree = await render();
  await nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier')).props.onClick();
  tree = await render();

  // Open preview so we also verify decision/conflict revokes preview URL
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 1);

  // Reject requires non-blank reason
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Reject Verification')).props.onClick();
  tree = await render();
  assert.equal(rejectCalls, 0);
  assert.match(text(tree), /Rejection reason is required/);

  // Enter reason and trigger stale 409 conflict -> auto-refreshes version to rev-v2 and preserves draft reason
  const textarea = nodes(tree).find((n) => n.type === 'textarea' && n.props.id === 'admin-rejection-reason');
  textarea.props.onChange({ target: { value: ' blurry signature on medical certificate ' } });
  tree = await render();
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Reject Verification')).props.onClick();
  tree = await render();

  assert.equal(rejectCalls, 1);
  assert.equal(revokedUrls.length, 1);
  assert.match(text(tree), /Doctor verification changed since you opened this dossier/);
  assert.equal(
    nodes(tree).find((n) => n.type === 'textarea' && n.props.id === 'admin-rejection-reason').props.value,
    ' blurry signature on medical certificate ',
  );

  // Concurrent duplicate approve clicks only dispatch once and send the refreshed rev-v2 token
  const approveBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Approve Verification'));
  approveBtn.props.onClick();
  approveBtn.props.onClick();
  approveGate.resolve();
  tree = await render();

  assert.equal(approveCalls, 1);
  const approvePost = postedActions.find((c) => c.url === '/doctors/101/verification/approve');
  assert.deepEqual(approvePost.body, { reviewVersion: 'rev-v2' });
  assert.match(text(tree), /has been marked VERIFIED/);
  unmount();
});

test('async race guard: rapid doctor switching ignores slow success, slow error, late preview Blob, and late decision', async () => {
  const doc101Gate = deferred();
  const doc101PreviewGate = deferred();
  const doc102DecisionGate = deferred();
  const doc102SwitchDetailsGate = deferred();

  const { render, unmount, createdUrls, revokedUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [
                makeQueueItem(101, { status: 'UNDER_REVIEW' }),
                makeQueueItem(102, { status: 'UNDER_REVIEW' }),
              ],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return await doc101Gate.promise;
        }
        if (url === '/doctors/101/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
        }
        if (url === '/doctors/102/verification') {
          if (doc102SwitchDetailsGate.active) {
            return await doc102SwitchDetailsGate.promise;
          }
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: makeDetails(102, { status: 'UNDER_REVIEW', registrationNumber: 'WINNER-102-REG' }),
          };
        }
        if (url === '/doctors/102/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(102) };
        }
        if (url === '/doctors/101/verification/documents/id-proof') {
          return await doc101PreviewGate.promise;
        }
        throw new Error(`Unexpected GET ${url}`);
      },
      post: async (url) => {
        if (url === '/doctors/102/verification/approve') {
          await doc102DecisionGate.promise;
          return { status: 204, data: null };
        }
        return { status: 204, data: null };
      },
    },
  });

  // 1. Click Doctor 101 (stays pending), immediately click Doctor 102 (resolves fast)
  let tree = await render();
  const btns1 = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  btns1[0].props.onClick(); // Doctor 101 slow
  btns1[1].props.onClick(); // Doctor 102 fast
  tree = await render();
  assert.match(text(tree), /WINNER-102-REG/);

  // Late Doctor 101 resolution must be completely ignored
  doc101Gate.resolve({
    status: 200,
    headers: JSON_HEADERS,
    data: makeDetails(101, { status: 'UNDER_REVIEW', registrationNumber: 'STALE-101-REG' }),
  });
  tree = await render();
  assert.match(text(tree), /WINNER-102-REG/);
  assert.doesNotMatch(text(tree), /STALE-101-REG/);

  // 2. Switch to Doctor 101 freshly, start Preview on 101 (pending), then switch to Doctor 102 BEFORE preview resolves
  const doc101FastDetails = {
    status: 200,
    headers: JSON_HEADERS,
    data: makeDetails(101, { status: 'UNDER_REVIEW', registrationNumber: 'FAST-101-REG' }),
  };
  doc101Gate.resolve = null;
  // Reset doc101 gate won't run if already settled, so let's instead start decision on Doctor 102, switch back, and reject!
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Approve Verification')).props.onClick();
  const err102 = new Error('Late decision error on 102');
  err102.response = { status: 500, data: { message: 'STALE-DECISION-ERR-102' } };

  // Switch queue filter to REJECTED and return error -> must clear previous queue items
  doc102SwitchDetailsGate.active = true;
  const reviewBtn101 = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  reviewBtn101.props.onClick();
  doc102DecisionGate.reject(err102);
  tree = await render();
  assert.doesNotMatch(text(tree), /STALE-DECISION-ERR-102/);
  assert.doesNotMatch(text(tree), /WINNER-102-REG/);
  unmount();
});

test('async race guard: late Doctor error and late preview Blob never overwrite newer doctor selection', async () => {
  const detailsGate101 = deferred();
  const previewGate101 = deferred();
  let preview101Mode = 'slow';

  const { render, unmount, createdUrls, revokedUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: 'UNDER_REVIEW' }), makeQueueItem(102, { status: 'UNDER_REVIEW' })],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          if (preview101Mode === 'fast') {
            return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status: 'UNDER_REVIEW', registrationNumber: 'DOC-101' }) };
          }
          return await detailsGate101.promise;
        }
        if (url === '/doctors/102/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(102, { status: 'UNDER_REVIEW', registrationNumber: 'DOC-102' }) };
        }
        if (url.endsWith('/documents')) {
          const did = url.includes('/101/') ? 101 : 102;
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(did) };
        }
        if (url === '/doctors/101/verification/documents/id-proof') {
          return await previewGate101.promise;
        }
        throw new Error(`Unexpected GET ${url}`);
      },
    },
  });

  // Slow Doctor 101 failure arrives after Doctor 102 loads
  let tree = await render();
  const initialBtns = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  initialBtns[0].props.onClick(); // 101 pending
  initialBtns[1].props.onClick(); // 102 resolves fast
  tree = await render();
  assert.match(text(tree), /DOC-102/);

  const lateErr = new Error('Late 101 error');
  lateErr.response = { status: 500, data: { message: 'LATE-ERR-101' } };
  detailsGate101.reject(lateErr);
  tree = await render();
  assert.match(text(tree), /DOC-102/);
  assert.doesNotMatch(text(tree), /LATE-ERR-101/);

  // Slow Doctor 101 preview Blob resolves AFTER switching to Doctor 102
  preview101Mode = 'fast';
  await nodes(tree).find((n) => n.type === 'button' && text(n) === 'Review Dossier').props.onClick();
  tree = await render();
  assert.match(text(tree), /DOC-101/);

  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  const doc102Btn = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier')).at(-1);
  doc102Btn.props.onClick();
  tree = await render();
  assert.match(text(tree), /DOC-102/);

  // Late preview resolves for Doctor 101 -> never renders img, zero leaked unrevoked URLs
  previewGate101.resolve({
    status: 200,
    headers: { 'content-type': 'image/png' },
    data: new Blob([PNG_BYTES], { type: 'image/png' }),
  });
  tree = await render();
  assert.equal(nodes(tree).filter((n) => n.type === 'img').length, 0);
  assert.equal(createdUrls.length, revokedUrls.length);
  unmount();
});

test('async race guard: queue filter race drops stale response and queue error clears stale items', async () => {
  const rejectedGate = deferred();
  let failVerified = false;

  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          if (config.params?.status === 'REJECTED') {
            return await rejectedGate.promise;
          }
          if (config.params?.status === 'VERIFIED') {
            if (failVerified) {
              const err = new Error('Queue failure');
              err.response = { status: 500, data: { message: 'Verified queue failed' } };
              throw err;
            }
            return {
              status: 200,
              headers: JSON_HEADERS,
              data: {
                items: [makeQueueItem(202, { status: 'VERIFIED' })],
                page: 0,
                size: 10,
                totalElements: 1,
                totalPages: 1,
                hasNext: false,
                hasPrevious: false,
              },
            };
          }
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: 'SUBMITTED' })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  assert.match(text(tree), /Doctor #101/);

  // Switch to REJECTED (slow) then VERIFIED (fast)
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Rejected').props.onClick();
  tree = await render();
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Verified').props.onClick();
  tree = await render();
  assert.match(text(tree), /Doctor #202/);

  // Late REJECTED response must NOT replace VERIFIED results
  rejectedGate.resolve({
    status: 200,
    headers: JSON_HEADERS,
    data: {
      items: [makeQueueItem(999, { status: 'REJECTED' })],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    },
  });
  tree = await render();
  assert.match(text(tree), /Doctor #202/);
  assert.doesNotMatch(text(tree), /Doctor #999/);

  // Queue refresh error on Verified must clear items instead of showing stale results
  failVerified = true;
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Refresh Queue').props.onClick();
  tree = await render();
  assert.match(text(tree), /Verified queue failed/);
  assert.doesNotMatch(text(tree), /Doctor #202/);
  unmount();
});

test('Start Review enforces 204 response and 409 conflict revokes preview and refreshes', async () => {
  let version = 'rev-101-v1';
  let status = 'SUBMITTED';
  const posted = [];
  let startStatusCode = 200;

  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status, reviewVersion: version }) };
        }
        return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
      },
      post: async (url, body) => {
        posted.push({ url, body: JSON.parse(JSON.stringify(body)) });
        if (startStatusCode === 200) {
          startStatusCode = 409;
          return { status: 200, data: {} };
        }
        version = 'rev-101-v2';
        status = 'VERIFIED';
        const err = new Error('Stale start review');
        err.response = {
          status: 409,
          data: { code: 'STALE_REVIEW_VERSION', message: 'Verification state changed before starting review.' },
        };
        throw err;
      },
    },
  });

  let tree = await render();
  await nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier')).props.onClick();
  tree = await render();

  // Unexpected 200 on POST /review throws UnexpectedApiResponse
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Start Review')).props.onClick();
  tree = await render();
  assert.deepEqual(posted[0], { url: '/doctors/101/verification/review', body: { reviewVersion: 'rev-101-v1' } });
  assert.match(text(tree), /unexpected response/);

  // 409 STALE_REVIEW_VERSION on POST /review refreshes state cleanly
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Start Review')).props.onClick();
  tree = await render();
  assert.match(text(tree), /Verification state changed before starting review/);
  assert.ok(nodes(tree).some((n) => n.props?.title === 'rev-101-v2'));
  assert.match(text(tree), /rev-101-v2/);
  unmount();
});

test('FIX 2 test 1: rapid filter switches ignore late error and keep latest filter active and rendered', async () => {
  const rejectedGate = deferred();
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          if (config.params?.status === 'REJECTED') {
            return await rejectedGate.promise;
          }
          if (config.params?.status === 'VERIFIED') {
            return {
              status: 200,
              headers: JSON_HEADERS,
              data: {
                items: [makeQueueItem(202, { status: 'VERIFIED' })],
                page: 0,
                size: 10,
                totalElements: 1,
                totalPages: 1,
                hasNext: false,
                hasPrevious: false,
              },
            };
          }
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: 'SUBMITTED' })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Rejected').props.onClick();
  tree = await render();
  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Verified').props.onClick();
  tree = await render();

  const err = new Error('Late rejected failure');
  err.response = { status: 500, data: { message: 'STALE-REJECTED-ERROR' } };
  rejectedGate.reject(err);
  tree = await render();

  assert.match(text(tree), /Doctor #202/);
  assert.doesNotMatch(text(tree), /STALE-REJECTED-ERROR/);
  const verifiedBtn = nodes(tree).find((n) => n.type === 'button' && text(n) === 'Verified');
  assert.match(verifiedBtn.props.className, /\bactive\b/);
  unmount();
});

test('FIX 2 test 2: clicking already-selected filter on page 0 does not clear visible queue items', async () => {
  let queueFetchCount = 0;
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          queueFetchCount += 1;
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101), makeQueueItem(102, { status: 'UNDER_REVIEW' })],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  assert.equal(queueFetchCount, 1);
  assert.match(text(tree), /Doctor #101/);
  assert.match(text(tree), /Doctor #102/);

  nodes(tree).find((n) => n.type === 'button' && text(n) === 'All Submissions').props.onClick();
  tree = await render();
  assert.match(text(tree), /Doctor #101/);
  assert.match(text(tree), /Doctor #102/);
  assert.doesNotMatch(text(tree), /No Verification Records/);
  assert.equal(queueFetchCount, 1);
  unmount();
});

test('FIX 2 test 3: slow page-change response resolving after filter switch is dropped cleanly', async () => {
  const page1Gate = deferred();
  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/admin/doctor-verifications') {
          if (config.params?.page === 1) {
            return await page1Gate.promise;
          }
          if (config.params?.status === 'SUBMITTED') {
            return {
              status: 200,
              headers: JSON_HEADERS,
              data: {
                items: [makeQueueItem(303, { status: 'SUBMITTED' })],
                page: 0,
                size: 10,
                totalElements: 1,
                totalPages: 1,
                hasNext: false,
                hasPrevious: false,
              },
            };
          }
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101)],
              page: 0,
              size: 10,
              totalElements: 15,
              totalPages: 2,
              hasNext: true,
              hasPrevious: false,
            },
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  assert.match(text(tree), /Doctor #101/);

  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Next Page')).props.onClick();
  tree = await render();
  assert.match(text(tree), /Loading verification queue/);

  nodes(tree).find((n) => n.type === 'button' && text(n) === 'Submitted').props.onClick();
  tree = await render();
  assert.match(text(tree), /Doctor #303/);

  page1Gate.resolve({
    status: 200,
    headers: JSON_HEADERS,
    data: {
      items: [makeQueueItem(999)],
      page: 1,
      size: 10,
      totalElements: 15,
      totalPages: 2,
      hasNext: false,
      hasPrevious: true,
    },
  });
  tree = await render();

  assert.match(text(tree), /Doctor #303/);
  assert.doesNotMatch(text(tree), /Doctor #999/);
  assert.match(text(tree), /Page 1 of 1/);
  unmount();
});

test('FIX 2 test 4: concurrent Refresh Queue requests resolving out of order keep latest response', async () => {
  let fetchSeq = 0;
  const firstRefreshGate = deferred();
  const secondRefreshGate = deferred();

  const { render, unmount } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          fetchSeq += 1;
          if (fetchSeq === 1) {
            return {
              status: 200,
              headers: JSON_HEADERS,
              data: {
                items: [makeQueueItem(101)],
                page: 0,
                size: 10,
                totalElements: 1,
                totalPages: 1,
                hasNext: false,
                hasPrevious: false,
              },
            };
          }
          if (fetchSeq === 2) {
            return await firstRefreshGate.promise;
          }
          return await secondRefreshGate.promise;
        }
        return { status: 200, headers: JSON_HEADERS, data: [] };
      },
    },
  });

  let tree = await render();
  assert.match(text(tree), /Doctor #101/);

  const refreshBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Refresh'));
  refreshBtn.props.onClick();
  refreshBtn.props.onClick();

  secondRefreshGate.resolve({
    status: 200,
    headers: JSON_HEADERS,
    data: {
      items: [makeQueueItem(502)],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    },
  });
  tree = await render();
  assert.match(text(tree), /Doctor #502/);

  firstRefreshGate.resolve({
    status: 200,
    headers: JSON_HEADERS,
    data: {
      items: [makeQueueItem(501)],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    },
  });
  tree = await render();

  assert.match(text(tree), /Doctor #502/);
  assert.doesNotMatch(text(tree), /Doctor #501/);
  unmount();
});

test('FIX 2 test 5: slow async parseApiErrorDetails on failed preview is ignored if user starts newer preview', async () => {
  const blobTextGate = deferred();
  let previewCalls = 0;

  const { render, unmount, createdUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [makeQueueItem(101, { status: 'UNDER_REVIEW' })],
              page: 0,
              size: 10,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status: 'UNDER_REVIEW' }) };
        }
        if (url === '/doctors/101/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(101) };
        }
        previewCalls += 1;
        if (previewCalls === 1) {
          const err = new Error('First preview failed');
          err.response = {
            status: 500,
            data: {
              text: () => blobTextGate.promise,
            },
          };
          throw err;
        }
        return {
          status: 200,
          headers: { 'content-type': 'image/jpeg' },
          data: new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }),
        };
      },
    },
  });

  let tree = await render();
  await nodes(tree).find((n) => n.type === 'button' && text(n).includes('Review Dossier')).props.onClick();
  tree = await render();

  // Start first preview (rejects, awaits slow blob.text())
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  await flush(2);

  // Start newer preview (Qualification succeeds cleanly)
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview Qualification')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 1);
  assert.ok(nodes(tree).find((n) => n.type === 'img' && n.props.src === createdUrls[0]));

  // Resolve slow first preview Blob.text() AFTER newer preview succeeded
  blobTextGate.resolve(JSON.stringify({ message: 'STALE-FIRST-PREVIEW-ERROR' }));
  tree = await render();

  assert.doesNotMatch(text(tree), /STALE-FIRST-PREVIEW-ERROR/);
  assert.ok(nodes(tree).find((n) => n.type === 'img' && n.props.src === createdUrls[0]));
  unmount();
});

test('FIX 2 test 6: slow async parseApiErrorDetails 409 preview error on Doctor A does not corrupt Doctor B', async () => {
  const blobText409Gate = deferred();
  const { render, unmount, createdUrls } = await setupAdminPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/admin/doctor-verifications') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: {
              items: [
                makeQueueItem(101, { status: 'UNDER_REVIEW' }),
                makeQueueItem(102, { status: 'UNDER_REVIEW' }),
              ],
              page: 0,
              size: 10,
              totalElements: 2,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          };
        }
        if (url === '/doctors/101/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(101, { status: 'UNDER_REVIEW', registrationNumber: 'REG-101' }) };
        }
        if (url === '/doctors/102/verification') {
          return { status: 200, headers: JSON_HEADERS, data: makeDetails(102, { status: 'UNDER_REVIEW', registrationNumber: 'REG-102' }) };
        }
        if (url === '/doctors/101/verification/documents' || url === '/doctors/102/verification/documents') {
          return { status: 200, headers: JSON_HEADERS, data: makeDocs(url.includes('/101/') ? 101 : 102) };
        }
        if (url === '/doctors/101/verification/documents/id-proof') {
          const err = new Error('409 conflict on 101');
          err.response = {
            status: 409,
            data: {
              text: () => blobText409Gate.promise,
            },
          };
          throw err;
        }
        if (url === '/doctors/102/verification/documents/id-proof') {
          return {
            status: 200,
            headers: { 'content-type': 'image/png' },
            data: new Blob([PNG_BYTES], { type: 'image/png' }),
          };
        }
        throw new Error(`Unexpected GET ${url}`);
      },
    },
  });

  let tree = await render();
  const reviewButtons = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier'));
  await reviewButtons[0].props.onClick();
  tree = await render();
  assert.match(text(tree), /REG-101/);

  // Trigger preview error on Doctor 101 with slow async blob.text()
  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  await flush(2);

  // Switch to Doctor 102 and open Doctor 102 preview
  const review102Btn = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Review Dossier')).at(-1);
  await review102Btn.props.onClick();
  tree = await render();
  assert.match(text(tree), /REG-102/);

  nodes(tree).find((n) => n.type === 'button' && text(n).includes('Preview ID Proof')).props.onClick();
  tree = await render();
  assert.equal(createdUrls.length, 1);

  // Resolve slow 409 blob error from Doctor 101 -> must NOT clear Doctor 102 preview or show 101 error
  blobText409Gate.resolve(JSON.stringify({ code: 'STALE_REVIEW_VERSION', message: 'STALE-101-409-ERROR' }));
  tree = await render();

  assert.match(text(tree), /REG-102/);
  assert.doesNotMatch(text(tree), /STALE-101-409-ERROR/);
  assert.ok(nodes(tree).find((n) => n.type === 'img' && n.props.src === createdUrls[0]));
  unmount();
});

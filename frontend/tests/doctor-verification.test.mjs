import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import { createApiClient } from '../src/api/axios.js';
import {
  requireDoctorVerificationDetails,
  requireNoContentResponse,
  requireUploadedVerificationDocument,
  requireVerificationDocuments,
} from '../src/api/responseValidation.js';
import {
  DOCUMENT_KIND_CONFIG,
  MAX_VERIFICATION_FILE_BYTES,
  VERIFICATION_STATUS_META,
  formatFileSize,
  formatVerificationError,
  formatVerificationTimestamp,
  isDraftVerificationStatus,
  parseApiErrorDetails,
  validateRegistrationCredentialsForm,
  validateVerificationImageFile,
} from '../src/utils/doctorVerification.js';
import { RequestSequencer, executeSequencedRequest } from '../src/utils/requestSequencer.js';

function componentSource(relPath, fnName) {
  return readFileSync(new URL(relPath, import.meta.url), 'utf8')
    .replace(/^import\s+[\s\S]*?\s+from\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace(/^import\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace(`export default function ${fnName}`, `function ${fnName}`);
}

const { code: panelCode } = await transformWithOxc(
  componentSource('../src/components/DoctorVerificationPanel.jsx', 'DoctorVerificationPanel'),
  'DoctorVerificationPanel.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } },
);

const { code: dashboardCode } = await transformWithOxc(
  componentSource('../src/pages/DoctorDashboard.jsx', 'DoctorDashboard'),
  'DoctorDashboard.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } },
);

async function flush(turns = 4) {
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

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);
const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);

function makeImageFile({ name = 'doc.png', type = 'image/png', bytes = PNG_BYTES, size = bytes.length } = {}) {
  return {
    name,
    type,
    size,
    slice(start, end) {
      return {
        async arrayBuffer() {
          return bytes.slice(start, end).buffer;
        },
      };
    },
  };
}

const SAMPLE_PROFILE = {
  id: 42,
  firstName: 'Aarav',
  lastName: 'Mehta',
  qualification: 'MBBS, MD (Medicine)',
  experience: 9,
  specialization: { id: 3, name: 'Internal Medicine' },
};

const JSON_HEADERS = { 'content-type': 'application/json' };

function makeVerificationPayload(overrides = {}) {
  return {
    doctorId: 42,
    status: 'UNVERIFIED',
    registrationNumber: null,
    medicalCouncil: null,
    registrationYear: null,
    latestRejectionReason: null,
    reviewVersion: 'sha256-ver-token-1',
    history: [],
    ...overrides,
  };
}

function makeDocPayload(kind, overrides = {}) {
  return {
    doctorId: 42,
    documentKind: kind,
    mediaType: 'image/png',
    fileSize: 2048,
    createdAt: '2026-11-10T10:15:00',
    ...overrides,
  };
}

async function setupPanel({ doctorProfile = SAMPLE_PROFILE, apiHandlers = {}, onVerificationChange } = {}) {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  const apiCalls = [];

  const defaultApi = {
    get: async (url, config = {}) => {
      apiCalls.push({ method: 'GET', url, config });
      if (url === `/doctors/${doctorProfile.id}/verification`) {
        return { status: 200, data: makeVerificationPayload(), headers: JSON_HEADERS };
      }
      if (url === `/doctors/${doctorProfile.id}/verification/documents`) {
        return { status: 200, data: [], headers: JSON_HEADERS };
      }
      throw new Error(`Unexpected GET ${url}`);
    },
    post: async (url, body, config = {}) => {
      apiCalls.push({ method: 'POST', url, body, config });
      return { status: 204, data: null };
    },
    postForm: async (url, formData, config = {}) => {
      apiCalls.push({ method: 'POST_FORM', url, formData, config });
      return { status: 201, data: makeDocPayload('ID_PROOF'), headers: JSON_HEADERS };
    },
    delete: async (url, config = {}) => {
      apiCalls.push({ method: 'DELETE', url, config });
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
    FormData,
    RequestSequencer,
    executeSequencedRequest,
    requireDoctorVerificationDetails,
    requireNoContentResponse,
    requireVerificationDocuments,
    requireUploadedVerificationDocument,
    DOCUMENT_KIND_CONFIG,
    VERIFICATION_STATUS_META,
    formatFileSize,
    formatVerificationError,
    formatVerificationTimestamp,
    isDraftVerificationStatus,
    parseApiErrorDetails,
    validateRegistrationCredentialsForm,
    validateVerificationImageFile,
  });

  const script = new vm.Script(`${panelCode}; DoctorVerificationPanel;`);
  const Component = script.runInContext(context);

  let latestTree;
  function renderSync() {
    cursor = 0;
    latestTree = Component({ doctorProfile, onVerificationChange });
    const effects = pendingEffects;
    pendingEffects = [];
    for (const run of effects) {
      run();
    }
    return latestTree;
  }

  async function settle(turns = 4) {
    renderSync();
    await flush(turns);
    return renderSync();
  }

  function unmount() {
    hooks.forEach((h) => h?.cleanup?.());
  }

  await settle();

  return { render: settle, unmount, getTree: () => latestTree, apiCalls };
}

test('createApiClient.postForm uploads FormData file field as multipart with X-CSRF-TOKEN', async () => {
  const seen = [];
  const client = createApiClient(async (request) => {
    seen.push({
      url: request.url,
      method: request.method,
      csrf: request.headers.get('X-CSRF-TOKEN'),
      contentType: request.headers.get('Content-Type'),
      isFormData: request.data instanceof FormData,
      hasFileField: request.data instanceof FormData ? request.data.has('file') : false,
    });
    if (request.url === '/auth/csrf') {
      return { status: 200, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-doc-123' }, headers: JSON_HEADERS };
    }
    return { status: 201, data: makeDocPayload('ID_PROOF'), headers: JSON_HEADERS };
  });

  const formData = new FormData();
  formData.append('file', new Blob([PNG_BYTES], { type: 'image/png' }), 'id.png');
  await client.postForm('/doctors/42/verification/documents/id-proof', formData);

  assert.equal(seen.length, 2);
  assert.equal(seen[0].url, '/auth/csrf');
  assert.equal(seen[1].url, '/doctors/42/verification/documents/id-proof');
  assert.equal(seen[1].csrf, 'csrf-doc-123');
  assert.match(String(seen[1].contentType), /multipart\/form-data/i);
  assert.equal(seen[1].isFormData, true);
  assert.equal(seen[1].hasFileField, true);
});

test('validateVerificationImageFile enforces size limit, MIME types, and binary signatures', async () => {
  const empty = makeImageFile({ size: 0, bytes: new Uint8Array([]) });
  assert.equal((await validateVerificationImageFile(empty)).valid, false);

  const oversized = makeImageFile({ size: MAX_VERIFICATION_FILE_BYTES + 1 });
  assert.match((await validateVerificationImageFile(oversized)).message, /5 MB/);

  const wrongMime = makeImageFile({ type: 'application/pdf' });
  assert.match((await validateVerificationImageFile(wrongMime)).message, /Only PNG and JPEG/);

  const spoofedPng = makeImageFile({ type: 'image/png', bytes: JPEG_BYTES, size: JPEG_BYTES.length });
  assert.match((await validateVerificationImageFile(spoofedPng)).message, /do not match/);

  const garbageBytes = makeImageFile({ type: 'image/png', bytes: new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]) });
  assert.match((await validateVerificationImageFile(garbageBytes)).message, /Invalid image file/);

  assert.equal((await validateVerificationImageFile(makeImageFile({ type: 'image/png', bytes: PNG_BYTES }))).valid, true);
  assert.equal((await validateVerificationImageFile(makeImageFile({ type: 'image/jpeg', bytes: JPEG_BYTES }))).valid, true);
});

test('DoctorDashboard exposes Verification tab keyed by doctorProfile.id', async () => {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  let renderedPanelProps = null;

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
      user: { id: 9, email: 'doc@hms.test', role: 'DOCTOR' },
      logout: async () => {},
      formDrafts: { get: () => null },
    }),
    useNavigate: () => () => {},
    api: {
      get: async (url) => {
        if (url === '/doctors') return { data: [{ ...SAMPLE_PROFILE, user: { id: 9 } }] };
        if (url === '/appointments') return { data: [] };
        return { data: [] };
      },
    },
    AvailabilityManager: () => ({ type: 'div', props: {}, children: [] }),
    DoctorVerificationPanel: (props) => {
      renderedPanelProps = props;
      return { type: 'section', props: { 'data-testid': 'doctor-verif-panel' }, children: [] };
    },
    AppointmentListState: (props) => ({ type: 'div', props: {}, children: props.children }),
    AppointmentMetricsState: () => ({ type: 'div', props: {}, children: [] }),
    requireAppointmentResult: (r) => r.data,
    requireAppointmentsList: (r) => r.data,
    mutationErrorMessage: () => 'err',
    RequestSequencer,
    executeSequencedRequest,
  });

  const script = new vm.Script(`${dashboardCode}; DoctorDashboard;`);
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
  tree = await settleDash();

  const verifBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Verification'));
  assert.ok(verifBtn, 'Verification tab button should be present');
  verifBtn.props.onClick();

  tree = await settleDash();
  assert.ok(renderedPanelProps, 'DoctorVerificationPanel should render when Verification tab is active');
  assert.equal(renderedPanelProps.doctorProfile.id, 42);
  hooks.forEach((h) => h?.cleanup?.());
});

test('DoctorVerificationPanel displays REJECTED reason and blocks leaking storage paths', async () => {
  const docId = makeDocPayload('ID_PROOF');
  const docQual = makeDocPayload('QUALIFICATION');
  const { render, unmount } = await setupPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/doctors/42/verification') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: makeVerificationPayload({
              status: 'REJECTED',
              registrationNumber: 'NMC-2018-99',
              medicalCouncil: 'Maharashtra Medical Council',
              registrationYear: 2018,
              latestRejectionReason: 'Medical council registration number looks cropped in upload',
              history: [
                {
                  previousStatus: 'UNDER_REVIEW',
                  newStatus: 'REJECTED',
                  actorRole: 'ADMIN',
                  occurredAt: '2026-11-10T11:30:00',
                },
              ],
            }),
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: [docId, docQual] };
      },
    },
  });

  const tree = await render();
  const fullText = text(tree);
  assert.match(fullText, /Internal Medicine/);
  assert.match(fullText, /MBBS, MD \(Medicine\)/);
  assert.match(fullText, /9 years/);
  assert.match(fullText, /REJECTED/);
  assert.match(fullText, /Medical council registration number looks cropped in upload/);
  assert.match(fullText, /Resubmit for Verification/);
  assert.doesNotMatch(fullText, /SHA-256 Checksum|checksum|storageKey|storagePath|\/documents\/42\/id-proof\//i);
  unmount();
});

test('503 storage-disabled upload shows clear notice and preserves unsaved credential form inputs', async () => {
  const { unmount: unmount503, render: render503 } = await setupPanel({
    apiHandlers: {
      get: async (url) => (
        url.endsWith('/documents')
          ? { status: 200, headers: JSON_HEADERS, data: [] }
          : { status: 200, headers: JSON_HEADERS, data: makeVerificationPayload() }
      ),
      postForm: async () => {
        const err = new Error('Storage unavailable');
        err.response = { status: 503, data: { message: 'Document storage service is temporarily unavailable' } };
        throw err;
      },
    },
  });

  let tree503 = await render503();
  nodes(tree503).find((n) => n.props.id === 'verif-reg-number')
    .props.onChange({ target: { value: 'NMC-DRAFT-77' } });
  tree503 = await render503();

  const fileInput = nodes(tree503).find((n) => n.type === 'input' && n.props.id === 'doctor-doc-input-id-proof');
  fileInput.props.onChange({ target: { files: [makeImageFile()], value: 'id.png' } });
  tree503 = await render503();

  assert.match(text(tree503), /Private document storage is temporarily unavailable on this server \(503\)/);
  const preservedInput = nodes(tree503).find((n) => n.props.id === 'verif-reg-number');
  assert.equal(preservedInput.props.value, 'NMC-DRAFT-77');
  unmount503();
});

test('upload, detach, credentials save, duplicate-submit protection, and submit work end-to-end', async () => {
  let status = 'UNVERIFIED';
  let docs = [];
  let savedCredentials = null;
  let submitCalls = 0;
  let uploadCalls = 0;

  const { render, unmount } = await setupPanel({
    apiHandlers: {
      get: async (url) => {
        if (url === '/doctors/42/verification') {
          return {
            status: 200,
            headers: JSON_HEADERS,
            data: makeVerificationPayload({
              status,
              registrationNumber: savedCredentials?.registrationNumber ?? null,
              medicalCouncil: savedCredentials?.medicalCouncil ?? null,
              registrationYear: savedCredentials?.registrationYear ?? null,
            }),
          };
        }
        return { status: 200, headers: JSON_HEADERS, data: docs };
      },
      postForm: async (url, formData) => {
        uploadCalls += 1;
        assert.ok(formData.get('file'), 'Multipart FormData must include "file" field');
        const kind = url.endsWith('id-proof') ? 'ID_PROOF' : 'QUALIFICATION';
        const uploaded = makeDocPayload(kind);
        docs = [...docs.filter((d) => d.documentKind !== kind), uploaded];
        return { status: 201, headers: JSON_HEADERS, data: uploaded };
      },
      delete: async (url) => {
        const kind = url.endsWith('id-proof') ? 'ID_PROOF' : 'QUALIFICATION';
        docs = docs.filter((d) => d.documentKind !== kind);
        return { status: 204 };
      },
      post: async (url, body) => {
        if (url === '/doctors/42/verification/credentials') {
          savedCredentials = body;
          return { status: 204, data: null };
        }
        if (url === '/doctors/42/verification/submit') {
          submitCalls += 1;
          status = 'SUBMITTED';
          return { status: 204, data: null };
        }
        throw new Error(`Unexpected POST ${url}`);
      },
    },
  });

  let tree = await render();

  // Invalid file must not call postForm
  const idInput = nodes(tree).find((n) => n.props.id === 'doctor-doc-input-id-proof');
  idInput.props.onChange({
    target: { files: [makeImageFile({ type: 'text/plain', bytes: new Uint8Array([65]) })], value: 'bad.txt' },
  });
  tree = await render();
  assert.equal(uploadCalls, 0);
  assert.match(text(tree), /Only PNG and JPEG images/);

  // Upload ID_PROOF then QUALIFICATION
  nodes(tree).find((n) => n.props.id === 'doctor-doc-input-id-proof')
    .props.onChange({ target: { files: [makeImageFile()], value: 'id.png' } });
  tree = await render();

  nodes(tree).find((n) => n.props.id === 'doctor-doc-input-qualification')
    .props.onChange({ target: { files: [makeImageFile({ type: 'image/jpeg', bytes: JPEG_BYTES })], value: 'qual.jpg' } });
  tree = await render();
  assert.equal(uploadCalls, 2);

  // Detach and re-upload ID_PROOF
  const removeButtons = nodes(tree).filter((n) => n.type === 'button' && text(n).includes('Remove Document'));
  assert.equal(removeButtons.length, 2);
  await removeButtons[0].props.onClick();
  tree = await render();
  assert.equal(docs.length, 1);

  nodes(tree).find((n) => n.props.id === 'doctor-doc-input-id-proof')
    .props.onChange({ target: { files: [makeImageFile()], value: 'id2.png' } });
  tree = await render();
  assert.equal(docs.length, 2);

  // Fill and save credentials
  nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.onChange({ target: { value: 'NMC-2020-8812' } });
  nodes(tree).find((n) => n.props.id === 'verif-med-council').props.onChange({ target: { value: 'Delhi Medical Council' } });
  nodes(tree).find((n) => n.props.id === 'verif-reg-year').props.onChange({ target: { value: '2020' } });
  tree = await render();

  const form = nodes(tree).find((n) => n.type === 'form');
  await form.props.onSubmit({ preventDefault() {} });
  tree = await render();
  assert.deepEqual(JSON.parse(JSON.stringify(savedCredentials)), {
    registrationNumber: 'NMC-2020-8812',
    medicalCouncil: 'Delhi Medical Council',
    registrationYear: 2020,
  });

  // Duplicate concurrent submit trigger only dispatches one POST /submit
  const submitBtn = nodes(tree).find((n) => n.type === 'button' && text(n).includes('Submit for Verification'));
  assert.equal(submitBtn.props.disabled, false);
  await Promise.all([submitBtn.props.onClick(), submitBtn.props.onClick()]);
  tree = await render();
  assert.equal(submitCalls, 1);
  assert.match(text(tree), /SUBMITTED/);
  assert.match(text(tree), /Locked in SUBMITTED status/);
  unmount();
});

test('strict HTTP status codes are enforced for upload (201) and no-content mutations (204)', () => {
  assert.throws(() => requireNoContentResponse({ status: 200, data: {} }));
  assert.doesNotThrow(() => requireNoContentResponse({ status: 204, data: null }));
  assert.throws(() => requireUploadedVerificationDocument({
    status: 200,
    headers: JSON_HEADERS,
    data: makeDocPayload('ID_PROOF'),
  }, 42, 'ID_PROOF'));
});

test('upload lock activates before async image signature validation to prevent concurrent uploads', async () => {
  const headerReadGate = deferred();
  let uploadCalls = 0;

  const { render, unmount } = await setupPanel({
    apiHandlers: {
      get: async (url) => (
        url.endsWith('/documents')
          ? { status: 200, headers: JSON_HEADERS, data: [] }
          : { status: 200, headers: JSON_HEADERS, data: makeVerificationPayload() }
      ),
      postForm: async () => {
        uploadCalls += 1;
        return { status: 201, headers: JSON_HEADERS, data: makeDocPayload('ID_PROOF') };
      },
    },
  });

  const delayedPngFile = {
    name: 'slow.png',
    type: 'image/png',
    size: PNG_BYTES.length,
    slice() {
      return {
        async arrayBuffer() {
          return headerReadGate.promise;
        },
      };
    },
  };
  const secondFile = makeImageFile({ name: 'second.png' });

  let tree = await render();
  const idInput = nodes(tree).find((n) => n.props.id === 'doctor-doc-input-id-proof');
  const qualInput = nodes(tree).find((n) => n.props.id === 'doctor-doc-input-qualification');

  const firstPromise = idInput.props.onChange({ target: { files: [delayedPngFile], value: 'slow.png' } });
  const secondPromise = qualInput.props.onChange({ target: { files: [secondFile], value: 'second.png' } });

  tree = await render();
  assert.match(text(tree), /Uploading\.\.\./);

  headerReadGate.resolve(PNG_BYTES.slice(0, 8).buffer);
  await Promise.all([firstPromise, secondPromise]);
  tree = await render();

  assert.equal(uploadCalls, 1);
  unmount();
});

test('400 field errors preserve dirty inputs and HTML proxy errors are stripped cleanly', async () => {
  const { render, unmount } = await setupPanel({
    apiHandlers: {
      get: async (url) => (
        url.endsWith('/documents')
          ? { status: 200, headers: JSON_HEADERS, data: [makeDocPayload('ID_PROOF'), makeDocPayload('QUALIFICATION')] }
          : { status: 200, headers: JSON_HEADERS, data: makeVerificationPayload() }
      ),
      post: async () => {
        const err = new Error('Validation failed');
        err.response = {
          status: 400,
          data: {
            status: 400,
            message: 'Validation failed',
            errors: { registrationYear: 'Registration year must be valid on server' },
          },
        };
        throw err;
      },
    },
  });

  let tree = await render();
  nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.onChange({ target: { value: 'NMC-DIRTY-1' } });
  nodes(tree).find((n) => n.props.id === 'verif-med-council').props.onChange({ target: { value: 'State Council' } });
  nodes(tree).find((n) => n.props.id === 'verif-reg-year').props.onChange({ target: { value: '2020' } });
  tree = await render();

  const form = nodes(tree).find((n) => n.type === 'form');
  await form.props.onSubmit({ preventDefault() {} });
  tree = await render();

  assert.match(text(tree), /Registration year must be valid on server/);
  assert.equal(nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.value, 'NMC-DIRTY-1');

  const htmlDetails = await parseApiErrorDetails({
    response: { status: 502, data: '<!DOCTYPE html><html><body>502 Bad Gateway</body></html>' },
  });
  assert.equal(htmlDetails.message, '');
  assert.equal(formatVerificationError(htmlDetails, 'upload'), 'Verification request failed. Please try again.');
  unmount();
});

test('credential inputs lock and ignore edits while verification submission is in flight', async () => {
  const submitGate = deferred();
  let currentStatus = 'UNVERIFIED';

  const { render, unmount } = await setupPanel({
    apiHandlers: {
      get: async (url) => (
        url.endsWith('/documents')
          ? { status: 200, headers: JSON_HEADERS, data: [makeDocPayload('ID_PROOF'), makeDocPayload('QUALIFICATION')] }
          : {
              status: 200,
              headers: JSON_HEADERS,
              data: makeVerificationPayload({
                status: currentStatus,
                registrationNumber: 'NMC-2020-48129',
                medicalCouncil: 'National Medical Commission',
                registrationYear: 2020,
              }),
            }
      ),
      post: async (url) => {
        if (url.endsWith('/submit')) {
          await submitGate.promise;
          currentStatus = 'SUBMITTED';
        }
        return { status: 204, data: null };
      },
    },
  });

  let tree = await render();
  const submitButton = nodes(tree).find(
    (n) => n.type === 'button' && text(n).includes('Submit for Verification'),
  );
  assert.ok(submitButton, 'Expected submit button to be rendered in draft state');

  const submitPromise = submitButton.props.onClick();
  tree = await render();

  const regNumberInput = nodes(tree).find((n) => n.props.id === 'verif-reg-number');
  const medCouncilInput = nodes(tree).find((n) => n.props.id === 'verif-med-council');
  const regYearInput = nodes(tree).find((n) => n.props.id === 'verif-reg-year');

  assert.equal(regNumberInput.props.disabled, true);
  assert.equal(medCouncilInput.props.disabled, true);
  assert.equal(regYearInput.props.disabled, true);

  regNumberInput.props.onChange({ target: { value: 'NMC-EDITED-MIDFLIGHT' } });
  medCouncilInput.props.onChange({ target: { value: 'Edited Council' } });
  regYearInput.props.onChange({ target: { value: '1999' } });
  tree = await render();

  assert.equal(nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.value, 'NMC-2020-48129');
  assert.equal(nodes(tree).find((n) => n.props.id === 'verif-med-council').props.value, 'National Medical Commission');
  assert.equal(String(nodes(tree).find((n) => n.props.id === 'verif-reg-year').props.value), '2020');

  submitGate.resolve();
  await submitPromise;
  tree = await render();

  assert.match(text(tree), /Your verification dossier has been submitted for administrative review\./);
  assert.equal(nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.value, 'NMC-2020-48129');
  assert.equal(nodes(tree).find((n) => n.props.id === 'verif-reg-number').props.disabled, true);
  unmount();
});

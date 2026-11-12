import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import { formatLocation, locationDraft, selectedLocationFilters } from '../src/utils/discoveryLocation.js';
import { RequestSequencer } from '../src/utils/requestSequencer.js';

function componentSource(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replace('export default function', 'function');
}

const { code: searchFormCode } = await transformWithOxc(
  [
    componentSource('../src/components/LocationAutocomplete.jsx'),
    componentSource('../src/components/DiscoverySearchForm.jsx'),
  ].join('\n'),
  'DiscoverySearchForm.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } }
);

const flush = () => new Promise((resolve) => setTimeout(resolve, 10));
const pastDebounce = () => new Promise((resolve) => setTimeout(resolve, 380));
const googleCity = (id, name, state) => ({ id, name, state, type: 'CITY', source: 'GOOGLE_PLACES', placeId: id });
const lgdDistrict = (id, name, state) => ({ id, name, state, type: 'DISTRICT', source: 'LGD' });
const KANPUR = googleCity('mock-kanpur', 'Kanpur', 'Uttar Pradesh');
const MUMBAI = googleCity('mock-mumbai', 'Mumbai', 'Maharashtra');
const LUCKNOW = googleCity('mock-lucknow', 'Lucknow', 'Uttar Pradesh');
const DELHI = googleCity('mock-delhi', 'Delhi', 'Delhi');
const MUMBAI_CENTRAL = {
  ...googleCity('mock-mumbai-central', 'Mumbai Central', 'Maharashtra'),
  type: 'AREA',
  parentCity: 'Mumbai',
};
const NEARBY_MUMBAI = { ...MUMBAI, source: 'GOOGLE_GEOCODING' };
const KANCHIPURAM = lgdDistrict('574', 'Kanchipuram', 'Tamil Nadu');
const MUMBAI_DISTRICT = lgdDistrict('479', 'Mumbai', 'Maharashtra');
const results = (suggestions, googleStatus = 'AVAILABLE') => ({ data: { suggestions, googleStatus } });

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

function findInput(tree) {
  return nodes(tree).find((n) => n?.type === 'input' && n?.props?.role === 'combobox');
}

function findOptions(tree) {
  return nodes(tree).filter((n) => n?.props?.role === 'option' && !n?.props?.className?.includes('__nearby'));
}

function findNearbyOption(tree) {
  return nodes(tree).find((n) => n?.props?.className?.includes('__nearby'));
}

function findStatus(tree) {
  return nodes(tree).find((n) => n?.props?.role === 'status' || n?.props?.role === 'alert');
}

async function setup({
  initialSearch = { city: '', specializationId: '', mode: '' },
  specializations = [{ id: 1, name: 'Cardiology' }, { id: 2, name: 'Dermatology' }],
  onSubmit = () => {},
  apiHandlers = {},
  geolocation,
} = {}) {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  const apiCalls = [];
  const documentListeners = {};

  const fakeDocument = {
    getElementById: () => null,
    addEventListener: (type, handler) => {
      documentListeners[type] ??= [];
      documentListeners[type].push(handler);
    },
    removeEventListener: (type, handler) => {
      if (documentListeners[type]) {
        documentListeners[type] = documentListeners[type].filter((h) => h !== handler);
      }
    },
    dispatch: (type, event) => {
      (documentListeners[type] || []).forEach((h) => h(event));
    },
  };

  const defaultApi = {
    get: async (url, config = {}) => {
      apiCalls.push({ url, config });
      if (url === '/v2/discovery/cities') {
        const q = config?.params?.q?.toLowerCase() || '';
        if (q.startsWith('kan')) {
          return results([KANPUR, KANCHIPURAM]);
        }
        if (q.startsWith('mum')) {
          return results([MUMBAI, MUMBAI_CENTRAL, MUMBAI_DISTRICT]);
        }
        if (q.startsWith('luc')) {
          return results([LUCKNOW]);
        }
        if (q.startsWith('del')) {
          return results([DELHI]);
        }
        return results([]);
      }
      return { data: [] };
    },
    post: async (url, body, config = {}) => {
      apiCalls.push({ url, body, config });
      if (url === '/v2/discovery/locations/resolve') {
        return { data: [KANPUR, MUMBAI, LUCKNOW, DELHI, MUMBAI_CENTRAL].find((item) => item.placeId === body.placeId) };
      }
      if (url === '/v2/discovery/locations/nearby') return { data: NEARBY_MUMBAI };
      return { data: null };
    },
    ...apiHandlers,
  };

  const context = vm.createContext({
    h: (type, props, ...children) => {
      if (typeof type === 'function') return type({ ...props, children });
      const node = { type, props: props ?? {}, children };
      if (props?.ref) {
        props.ref.current = {
          contains: (target) => target && target !== 'OUTSIDE' && target?.nodeName !== 'BODY',
          focus: () => {},
        };
      }
      return node;
    },
    Fragment: Symbol('Fragment'),
    useId: () => ':location:',
    RequestSequencer,
    formatLocation,
    locationDraft,
    selectedLocationFilters,
    googleMapsAttribution: '/mock-google-maps-logo.png',
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
    crypto: { randomUUID },
    navigator: { geolocation },
    AbortController,
    document: fakeDocument,
    setTimeout,
    clearTimeout,
  });

  const script = new vm.Script(`${searchFormCode}; DiscoverySearchForm;`);
  const Component = script.runInContext(context);

  let currentProps = { initialSearch, specializations, onSubmit };

  async function render(nextProps) {
    if (nextProps) {
      currentProps = { ...currentProps, ...nextProps };
    }
    cursor = 0;
    const tree = Component(currentProps);
    const effectsToRun = pendingEffects;
    pendingEffects = [];
    for (const run of effectsToRun) {
      run();
    }
    await flush();
    return tree;
  }

  function unmount() {
    hooks.forEach((h) => h?.cleanup?.());
  }

  const initialTree = await render();

  return {
    render,
    unmount,
    getTree: () => initialTree,
    apiCalls,
    fakeDocument,
  };
}

test('focus and short inputs show only Use my location without requesting permission', async () => {
  let permissionRequests = 0;
  const { render, apiCalls } = await setup({
    geolocation: { getCurrentPosition: () => { permissionRequests += 1; } },
  });

  let tree = await render();
  const input = findInput(tree);
  assert.ok(input, 'Combobox input should exist');
  assert.equal(input.props['autoComplete'], 'off');
  assert.equal(input.props['aria-expanded'], false);

  input.props.onFocus();
  tree = await render();
  assert.match(text(findNearbyOption(tree)), /Use my location/);
  findInput(tree).props.onChange({ target: { value: 'k' } });
  // Wait past debounce
  await pastDebounce();
  tree = await render();

  assert.equal(apiCalls.length, 0, 'No API call should be made for 1 character');
  assert.equal(findOptions(tree).length, 0, 'No location suggestions should be shown');
  const updatedInput = findInput(tree);
  assert.equal(updatedInput.props['aria-expanded'], true);
  assert.equal(permissionRequests, 0);
});

test('suggestions appear after two typed characters with debouncing', async () => {
  const { render, apiCalls } = await setup();

  let tree = await render();
  const input = findInput(tree);

  // Type 'kan'
  input.props.onChange({ target: { value: 'kan' } });

  // Before debounce completes, no API call
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(apiCalls.length, 0, 'Debounce should delay the API call');

  // After debounce completes
  await new Promise((r) => setTimeout(r, 280));
  await flush();
  tree = await render();

  assert.equal(apiCalls.length, 1, 'Exactly one API call should be made after debounce');
  assert.equal(apiCalls[0].url, '/v2/discovery/cities');
  assert.equal(apiCalls[0].config.params.q, 'kan');

  const options = findOptions(tree);
  assert.equal(options.length, 2, 'Two canonical suggestions should be rendered');
  assert.match(text(options[0]), /Kanpur.*Uttar Pradesh.*CITY/);
  assert.match(text(options[1]), /Kanchipuram.*Tamil Nadu.*DISTRICT/);

  const updatedInput = findInput(tree);
  assert.equal(updatedInput.props['aria-expanded'], true);
});

test('click-to-select suggestion updates city field and closes dropdown', async () => {
  let submittedPayload = null;
  const { render } = await setup({
    onSubmit: (payload) => {
      submittedPayload = payload;
    },
  });

  let tree = await render();
  const input = findInput(tree);

  // Type 'kan' and wait for suggestions
  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  const options = findOptions(tree);
  assert.equal(options.length, 2);

  await options[0].props.onClick();
  tree = await render();

  // Dropdown should be closed and suggestions cleared
  assert.equal(findOptions(tree).length, 0);
  const updatedInput = findInput(tree);
  assert.equal(updatedInput.props.value, 'Kanpur, Uttar Pradesh');
  assert.equal(updatedInput.props['aria-expanded'], false);

  // Submit form
  const form = nodes(tree).find((n) => n?.type === 'form');
  form.props.onSubmit({ preventDefault: () => {} });
  assert.equal(submittedPayload?.city, '');
  assert.equal(submittedPayload?.locationId, 'mock-kanpur');
  assert.equal(submittedPayload?.locationType, 'CITY');
  assert.equal(submittedPayload?.locationName, 'Kanpur');
  assert.equal(submittedPayload?.locationState, 'Uttar Pradesh');
});

test('keyboard navigation with ArrowDown, ArrowUp, Enter, and Escape', async () => {
  const { render } = await setup();

  let tree = await render();
  const input = findInput(tree);

  // Type 'kan' and load suggestions
  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  let options = findOptions(tree);
  assert.equal(options.length, 2);
  assert.equal(options[0].props['aria-selected'], false);
  assert.equal(options[1].props['aria-selected'], false);

  // The geolocation action is first, before the suggestions.
  let currentInput = findInput(tree);
  currentInput.props.onKeyDown({ key: 'ArrowDown', preventDefault: () => {} });
  tree = await render();
  assert.equal(findNearbyOption(tree).props['aria-selected'], true);
  currentInput = findInput(tree);
  currentInput.props.onKeyDown({ key: 'ArrowDown', preventDefault: () => {} });
  tree = await render();
  options = findOptions(tree);
  assert.equal(options[0].props['aria-selected'], true);
  assert.equal(options[1].props['aria-selected'], false);

  // ArrowDown again -> highlight second item (Kanchipuram)
  currentInput = findInput(tree);
  currentInput.props.onKeyDown({ key: 'ArrowDown', preventDefault: () => {} });
  tree = await render();
  options = findOptions(tree);
  assert.equal(options[0].props['aria-selected'], false);
  assert.equal(options[1].props['aria-selected'], true);

  // ArrowUp -> back to first item (Kanpur)
  currentInput = findInput(tree);
  currentInput.props.onKeyDown({ key: 'ArrowUp', preventDefault: () => {} });
  tree = await render();
  options = findOptions(tree);
  assert.equal(options[0].props['aria-selected'], true);
  assert.equal(options[1].props['aria-selected'], false);

  // Enter -> commit first item
  currentInput = findInput(tree);
  currentInput.props.onKeyDown({ key: 'Enter', preventDefault: () => {} });
  await flush();
  tree = await render();
  assert.equal(findOptions(tree).length, 0, 'Dropdown should close after Enter selection');
  assert.equal(findInput(tree).props.value, 'Kanpur, Uttar Pradesh');

  // Open again with 'kan' and test Escape
  currentInput = findInput(tree);
  currentInput.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();
  assert.equal(findOptions(tree).length, 2);

  // Escape -> closes dropdown
  findInput(tree).props.onKeyDown({ key: 'Escape', preventDefault: () => {} });
  tree = await render();
  assert.equal(findOptions(tree).length, 0);
  assert.equal(findInput(tree).props['aria-expanded'], false);
});

test('empty state renders when no matching cities are returned', async () => {
  const { render } = await setup();

  let tree = await render();
  const input = findInput(tree);

  // Type non-matching city prefix
  input.props.onChange({ target: { value: 'xyz' } });
  await pastDebounce();
  await flush();
  tree = await render();

  const status = findStatus(tree);
  assert.ok(status, 'Status element should be present');
  assert.match(text(status), /No matching locations found/i);
});

test('error state renders when city suggestion fetch fails', async () => {
  const { render } = await setup({
    apiHandlers: {
      get: async (url) => {
        if (url === '/v2/discovery/cities') {
          throw new Error('Network error');
        }
        return { data: [] };
      },
    },
  });

  let tree = await render();
  const input = findInput(tree);

  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  const status = findStatus(tree);
  assert.ok(status, 'Error status element should be present');
  assert.match(text(status), /Unable to load location suggestions/i);
});

test('click-outside dismisses the autocomplete dropdown', async () => {
  const { render, fakeDocument } = await setup();

  let tree = await render();
  const input = findInput(tree);

  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  assert.equal(findOptions(tree).length, 2, 'Dropdown should be open');

  // Dispatch click outside wrapper
  fakeDocument.dispatch('pointerdown', { target: { nodeName: 'BODY' } });
  tree = await render();

  assert.equal(findOptions(tree).length, 0, 'Dropdown should close on click-outside');
  assert.equal(findInput(tree).props['aria-expanded'], false);
});

test('specialty and consultation mode selections remain undisturbed', async () => {
  let submittedPayload = null;
  const { render } = await setup({
    initialSearch: { city: 'Mumbai', specializationId: '1', mode: 'VIDEO' },
    onSubmit: (payload) => {
      submittedPayload = payload;
    },
  });

  let tree = await render();
  const selects = nodes(tree).filter((n) => n?.type === 'select');
  assert.equal(selects.length, 2, 'Should render Specialty and Mode selects');

  // Specialty select
  assert.equal(selects[0].props.value, '1');
  // Mode select
  assert.equal(selects[1].props.value, 'VIDEO');

  // Update mode
  selects[1].props.onChange({ target: { value: 'IN_CLINIC' } });
  tree = await render();

  // Submit form
  const form = nodes(tree).find((n) => n?.type === 'form');
  form.props.onSubmit({ preventDefault: () => {} });

  assert.equal(submittedPayload?.city, 'Mumbai');
  assert.equal(submittedPayload?.specializationId, '1');
  assert.equal(submittedPayload?.mode, 'IN_CLINIC');
});

test('HTML response displays error state', async () => {
  const { render } = await setup({
    apiHandlers: {
      get: async (url) => {
        if (url === '/v2/discovery/cities') {
          return { data: '<!doctype html><html><body>Login</body></html>' };
        }
        return { data: [] };
      },
    },
  });

  let tree = await render();
  const input = findInput(tree);

  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  const status = findStatus(tree);
  assert.ok(status, 'Status element should be rendered');
  assert.match(text(status), /Unable to load location suggestions/i);
  assert.equal(findOptions(tree).length, 0);
});

test('unexpected object response displays error state', async () => {
  const { render } = await setup({
    apiHandlers: {
      get: async (url) => {
        if (url === '/v2/discovery/cities') {
          return { data: { status: 404, message: 'Not found' } };
        }
        return { data: [] };
      },
    },
  });

  let tree = await render();
  const input = findInput(tree);

  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  const status = findStatus(tree);
  assert.ok(status, 'Status element should be rendered');
  assert.match(text(status), /Unable to load location suggestions/i);
  assert.equal(findOptions(tree).length, 0);
});

test('canceled requests do not display errors', async () => {
  const { render } = await setup({
    apiHandlers: {
      get: async (url, config) => {
        if (url === '/v2/discovery/cities') {
          const err = new Error('canceled');
          err.name = 'CanceledError';
          throw err;
        }
        return { data: [] };
      },
    },
  });

  let tree = await render();
  const input = findInput(tree);

  input.props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  await flush();
  tree = await render();

  // For a canceled request, no error should be shown in dropdown and loading resets
  const errorStatus = nodes(tree).find((n) => n?.props?.role === 'alert' || n?.props?.className?.includes('error'));
  assert.equal(errorStatus, undefined, 'Canceled request should not display an error status');
  const loadingStatus = nodes(tree).find((n) => n?.props?.className?.includes('loading') || text(n).includes('Searching'));
  assert.equal(loadingStatus, undefined, 'Loading state should be reset on cancellation');
});

test('Mumbai city and Mumbai district remain distinct and carry state labels', async () => {
  let submitted;
  const { render } = await setup({ onSubmit: (value) => { submitted = value; } });
  findInput(await render()).props.onChange({ target: { value: 'mum' } });
  await pastDebounce();
  let tree = await render();
  const options = findOptions(tree);
  assert.equal(options.length, 3);
  assert.match(text(options[0]), /Mumbai.*Maharashtra.*CITY/);
  assert.match(text(options[1]), /Mumbai Central.*Mumbai, Maharashtra.*AREA/);
  assert.match(text(options[2]), /Mumbai.*Maharashtra.*DISTRICT/);
  assert.equal(nodes(tree).find((node) => node.type === 'img')?.props.alt, 'Google Maps');
  assert.equal(nodes(tree).filter((node) => node.props?.role === 'group').length, 2);

  options[2].props.onClick();
  tree = await render();
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(submitted?.locationId, MUMBAI_DISTRICT.id);
  assert.equal(submitted?.locationType, 'DISTRICT');
});

for (const [query, expected] of [
  ['luc', /Lucknow.*Uttar Pradesh.*CITY/],
  ['del', /Delhi.*CITY/],
]) {
  test(`a city is suggested for ${query}`, async () => {
    const { render } = await setup();
    findInput(await render()).props.onChange({ target: { value: query } });
    await pastDebounce();
    const [option] = findOptions(await render());
    assert.match(text(option), expected);
  });
}

test('district-only fallback stays selectable without Google attribution', async () => {
  const { render } = await setup({ apiHandlers: { get: async () => results([KANCHIPURAM], 'UNAVAILABLE') } });
  findInput(await render()).props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  const tree = await render();
  const [option] = findOptions(tree);
  assert.match(text(option), /Kanchipuram.*Tamil Nadu.*DISTRICT/);
  assert.match(text(tree), /City and area suggestions may be unavailable/);
  assert.equal(nodes(tree).some((node) => node.type === 'img'), false);
});

test('arbitrary typed locations and edits after a selection cannot be submitted', async () => {
  let submitted = 0;
  const { render } = await setup({ onSubmit: () => { submitted += 1; } });
  findInput(await render()).props.onChange({ target: { value: 'kan' } });
  let tree = await render();
  tree.props.onSubmit({ preventDefault() {} });
  tree = await render();
  assert.equal(submitted, 0);
  assert.equal(findInput(tree).props['aria-invalid'], true);
  assert.match(text(tree), /Select a location from the suggestions/);

  await pastDebounce();
  await findOptions(await render())[0].props.onClick();
  tree = await render();
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(submitted, 1);
  findInput(tree).props.onChange({ target: { value: 'k' } });
  tree = await render();
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(submitted, 1);
});

test('typing immediately aborts and ignores an older response during the new debounce', async () => {
  let oldSignal;
  let resolveOld;
  const oldResponse = new Promise((resolve) => { resolveOld = resolve; });
  const { render } = await setup({
    apiHandlers: {
      get: async (_url, { params, signal }) => {
        if (params.q === 'kan') {
          oldSignal = signal;
          return oldResponse;
        }
        return results([LUCKNOW]);
      },
    },
  });
  findInput(await render()).props.onChange({ target: { value: 'kan' } });
  await pastDebounce();
  assert.equal(oldSignal?.aborted, false);

  findInput(await render()).props.onChange({ target: { value: 'luc' } });
  assert.equal(oldSignal.aborted, true);
  resolveOld(results([KANPUR]));
  await flush();
  const pending = await render();
  assert.equal(findOptions(pending).length, 0);
  assert.match(text(pending), /Searching locations/);

  await pastDebounce();
  const options = findOptions(await render());
  assert.equal(options.length, 1);
  assert.match(text(options[0]), /Lucknow/);
});

test('an area selection resolves with the same session token and retains its parent city', async () => {
  let submitted;
  const { render, apiCalls } = await setup({ onSubmit: (value) => { submitted = value; } });
  findInput(await render()).props.onChange({ target: { value: 'mu' } });
  await pastDebounce();
  const firstToken = apiCalls[0].config.params.sessionToken;
  assert.match(firstToken, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  findInput(await render()).props.onChange({ target: { value: 'mum' } });
  await pastDebounce();
  assert.equal(apiCalls[1].config.params.sessionToken, firstToken);

  await findOptions(await render())[1].props.onClick();
  assert.equal(apiCalls[2].url, '/v2/discovery/locations/resolve');
  assert.deepEqual({ ...apiCalls[2].body }, { placeId: MUMBAI_CENTRAL.placeId, sessionToken: firstToken });
  const tree = await render();
  assert.equal(findInput(tree).props.value, 'Mumbai Central, Mumbai, Maharashtra');
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(submitted?.locationType, 'AREA');
  assert.equal(submitted?.locationParentCity, 'Mumbai');

  findInput(tree).props.onChange({ target: { value: 'del' } });
  await pastDebounce();
  assert.notEqual(apiCalls[3].config.params.sessionToken, firstToken);
});

test('Use my location sends rounded coordinates after the click and waits for city selection', async () => {
  let permissionRequests = 0;
  let submitted;
  const { render, apiCalls } = await setup({
    geolocation: {
      getCurrentPosition: (success) => {
        permissionRequests += 1;
        success({ coords: { latitude: 19.074881, longitude: 72.877682 } });
      },
    },
    onSubmit: (value) => { submitted = value; },
  });
  findInput(await render()).props.onFocus();
  const open = await render();
  assert.equal(permissionRequests, 0);
  assert.equal(apiCalls.length, 0);
  findNearbyOption(open).props.onClick();
  await flush();
  let tree = await render();
  assert.equal(permissionRequests, 1);
  assert.equal(apiCalls[0].url, '/v2/discovery/locations/nearby');
  assert.deepEqual({ ...apiCalls[0].body }, { latitude: 19.075, longitude: 72.878 });
  assert.equal(findInput(tree).props.value, '', 'Nearby lookup should not select automatically');
  assert.match(text(tree), /Select the nearby city to use it/);
  const [nearbyCity] = findOptions(tree);
  await nearbyCity.props.onClick();
  tree = await render();
  assert.equal(findInput(tree).props.value, 'Mumbai, Maharashtra');
  assert.equal(apiCalls.length, 1, 'Nearby city should not make another Place Details request');
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(submitted?.locationId, MUMBAI.placeId);
});

for (const [code, message] of [
  [1, /permission was denied/],
  [2, /location is unavailable/],
  [3, /request timed out/],
]) {
  test(`geolocation failure ${code} displays the appropriate message without sending coordinates`, async () => {
    const { render, apiCalls } = await setup({
      geolocation: { getCurrentPosition: (_success, fail) => fail({ code }) },
    });
    findInput(await render()).props.onFocus();
    findNearbyOption(await render()).props.onClick();
    const tree = await render();
    assert.match(text(findStatus(tree)), message);
    assert.equal(apiCalls.length, 0);
    assert.equal(findOptions(tree).length, 0);
  });
}

test('editing the input discards a stale geolocation callback before it transmits coordinates', async () => {
  let geolocationCallback;
  const { render, apiCalls } = await setup({
    geolocation: { getCurrentPosition: (success) => { geolocationCallback = success; } },
  });
  findInput(await render()).props.onFocus();
  findNearbyOption(await render()).props.onClick();
  findInput(await render()).props.onChange({ target: { value: 'luc' } });
  geolocationCallback({ coords: { latitude: 19.075, longitude: 72.878 } });
  await pastDebounce();
  assert.equal(apiCalls.length, 1);
  assert.equal(apiCalls[0].url, '/v2/discovery/cities');
});

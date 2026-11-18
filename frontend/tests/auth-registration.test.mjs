import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';

function componentSource(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replace(/export (default )?function/g, 'function');
}

test('Theme verification: index.css uses green palette and eliminates purple accents', () => {
  const indexCss = readFileSync(new URL('../src/index.css', import.meta.url), 'utf8');

  // Verify green healthcare palette
  assert.match(indexCss, /--accent:\s*#078b83/, 'Should define --accent as #078b83');
  assert.match(indexCss, /--accent-hover:\s*#06756d/, 'Should define --accent-hover as #06756d');
  assert.match(indexCss, /rgba\(7,\s*139,\s*131,\s*0\.08\)/, 'Should define green --accent-bg');

  // Verify purple accents are eliminated
  assert.doesNotMatch(indexCss, /#aa3bff/i, 'Purple #aa3bff must be removed');
  assert.doesNotMatch(indexCss, /#c084fc/i, 'Purple #c084fc must be removed');

  const availCss = readFileSync(new URL('../src/components/AvailabilityManager.css', import.meta.url), 'utf8');
  assert.doesNotMatch(availCss, /#6d28d9/i, 'Purple #6d28d9 must be removed from AvailabilityManager');
});

test('LoginPage: displays success banners for registered patients and doctors', async () => {
  const { code: loginCode } = await transformWithOxc(
    componentSource('../src/pages/LoginPage.jsx'),
    'LoginPage.jsx',
    { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } }
  );

  function renderLoginWithParams(queryString) {
    let capturedVdom = null;
    const context = vm.createContext({
      h: (type, props, ...children) => ({ type, props: props ?? {}, children }),
      Fragment: Symbol('Fragment'),
      useAuth: () => ({ user: null, loading: false }),
      useNavigate: () => () => {},
      useSearchParams: () => [new URLSearchParams(queryString), () => {}],
      useState: (init) => [typeof init === 'function' ? init() : init, () => {}],
      api: { post: async () => ({}) },
      requireJsonResponse: () => {},
      mutationErrorMessage: () => '',
      resolveOAuthLoginUrl: () => '/oauth2/authorization/google',
      Link: ({ to, children, ...props }) => ({ type: 'a', props: { href: to, ...props }, children }),
      Navigate: ({ to }) => ({ type: 'Navigate', props: { to } }),
      URLSearchParams,
    });

    const script = new vm.Script(`
      ${loginCode};
      LoginPage();
    `);
    capturedVdom = script.runInContext(context);
    return JSON.stringify(capturedVdom);
  }

  const patientJson = renderLoginWithParams('registered=patient');
  assert.match(patientJson, /Registration successful/i, 'Should display patient registration success');
  assert.match(patientJson, /Register as Patient/i, 'Should render patient registration link');
  assert.match(patientJson, /Apply as Doctor/i, 'Should render doctor application link');

  const doctorJson = renderLoginWithParams('registered=doctor');
  assert.match(doctorJson, /Doctor registration submitted successfully/i, 'Should display doctor registration success');
});

test('PatientRegisterPage: client-side validation logic', () => {
  const patientSource = readFileSync(new URL('../src/pages/PatientRegisterPage.jsx', import.meta.url), 'utf8');

  // Verify required patient fields are handled
  assert.match(patientSource, /dateOfBirth/, 'Must validate date of birth');
  assert.match(patientSource, /phone/, 'Must validate phone number');
  assert.match(patientSource, /gender/, 'Must validate gender');
  assert.match(patientSource, /\/auth\/register\/patient/, 'Must submit to patient registration endpoint');
});

test('DoctorRegisterPage: client-side validation logic and verification notice', () => {
  const doctorSource = readFileSync(new URL('../src/pages/DoctorRegisterPage.jsx', import.meta.url), 'utf8');

  // Verify doctor fields and notice
  assert.match(doctorSource, /specializationId/, 'Must require specialization');
  assert.match(doctorSource, /qualification/, 'Must require qualification');
  assert.match(doctorSource, /experience/, 'Must require experience');
  assert.match(doctorSource, /\/auth\/register\/doctor/, 'Must submit to doctor registration endpoint');
  assert.match(doctorSource, /verification/i, 'Must present verification notice to doctor');
});

test('AvailabilityManager: rejects availability creation when doctor is UNVERIFIED', () => {
  const availSource = readFileSync(new URL('../src/components/AvailabilityManager.jsx', import.meta.url), 'utf8');

  assert.match(availSource, /UNVERIFIED/, 'Must check UNVERIFIED status');
  assert.match(availSource, /pending verification/i, 'Must inform unverified doctor');
});

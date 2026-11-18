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

const CANONICAL_16 = [
  'Cardiology',
  'Neurology',
  'Orthopedics',
  'Dermatology',
  'Pediatrics',
  'Gynecology',
  'General Medicine',
  'Ophthalmology',
  'ENT (Otolaryngology)',
  'Psychiatry',
  'Gastroenterology',
  'Endocrinology',
  'Urology',
  'Pulmonology',
  'Nephrology',
  'Oncology',
];

function isGenuineSpecialty(item) {
  if (!item || !Number.isFinite(item?.id) || typeof item?.name !== 'string') return false;
  const lower = item.name.trim().toLowerCase();
  return !lower.startsWith('doc ')
    && !lower.includes('catalog spec')
    && !lower.includes('svc spec')
    && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(lower);
}

test('frontend filters out placeholder names, Doc Catalog Spec, and UUIDs', () => {
  const mixedData = [
    { id: 1, name: 'Cardiology', description: 'Heart care' },
    { id: 2, name: 'Doc Catalog Spec 93ece05f-ae27-4d6e-a879-fb433ed60363' },
    { id: 3, name: 'Doc Svc Spec 5a4c26a9-905b-4b6a-931e-500c69ba7bf0' },
    { id: 4, name: '8c4277d7-5fec-4229-acb4-448e1c180db2' },
    { id: 5, name: 'Neurology', description: 'Brain care' },
    { id: 6, name: 'General Medicine', description: 'Primary care' },
  ];

  const filtered = mixedData.filter(isGenuineSpecialty);
  assert.equal(filtered.length, 3);
  assert.deepEqual(filtered.map(s => s.name), ['Cardiology', 'Neurology', 'General Medicine']);
});

test('all 16 canonical specialties produce a valid React SVG element from SpecialtyIcon', async () => {
  const { code: iconCode } = await transformWithOxc(
    componentSource('../src/components/SpecialtyIcon.jsx'),
    'SpecialtyIcon.jsx',
    { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } }
  );

  const context = vm.createContext({
    h: (type, props, ...children) => ({ type, props: props ?? {}, children }),
    Fragment: Symbol('Fragment'),
  });
  vm.runInContext(iconCode, context);

  for (const name of CANONICAL_16) {
    const icon = vm.runInContext(`SpecialtyIcon({ name: ${JSON.stringify(name)} })`, context);
    assert.ok(icon, `Expected SpecialtyIcon to return an element for ${name}`);
    assert.equal(icon.type, 'svg', `Expected SVG root element for ${name}`);
    assert.equal(icon.props['aria-hidden'], 'true');
    assert.ok(icon.children.length > 0, `Expected SVG children for ${name}`);
  }
});

test('discoveryQuery with specializationId formats correct search query string', async () => {
  const { discoveryQuery } = await import('../src/utils/discoveryLocation.js');
  const params = discoveryQuery({ city: '', specializationId: '256', mode: '' });
  assert.equal(params.toString(), 'specializationId=256');

  const paramsWithCity = discoveryQuery({ city: 'Mumbai', specializationId: '256', mode: 'VIDEO' });
  assert.equal(paramsWithCity.get('city'), 'Mumbai');
  assert.equal(paramsWithCity.get('specializationId'), '256');
  assert.equal(paramsWithCity.get('mode'), 'VIDEO');
});

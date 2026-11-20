import assert from 'node:assert/strict';
import test from 'node:test';
import { isBill, formatCurrency, createBillService, BILL_STATUSES } from '../src/api/billService.js';
import { UnexpectedApiResponse } from '../src/api/responseValidation.js';

test('isBill validates valid and invalid bill objects', () => {
  const validBill = {
    id: 1,
    invoiceNumber: 'INV-20261115-A1B2C3D4',
    amount: '500.00',
    currency: 'INR',
    status: 'UNPAID',
  };

  assert.equal(isBill(validBill), true, 'Valid bill must pass isBill');

  assert.equal(isBill({ ...validBill, id: 0 }), false, 'id must be positive safe integer');
  assert.equal(isBill({ ...validBill, invoiceNumber: 'BAD-123' }), false, 'invoiceNumber must start with INV-');
  assert.equal(isBill({ ...validBill, currency: 'USD' }), false, 'currency must be INR');
  assert.equal(isBill({ ...validBill, status: 'UNKNOWN' }), false, 'status must be UNPAID, PAID, or VOID');
  assert.equal(isBill(null), false, 'null must fail');
});

test('formatCurrency formats Indian Rupee correctly', () => {
  assert.equal(formatCurrency(500), '₹500.00');
  assert.equal(formatCurrency('750.50'), '₹750.50');
  assert.equal(formatCurrency(0), '₹0.00');
  assert.equal(formatCurrency(null), '₹0.00');
  assert.equal(formatCurrency(undefined), '₹0.00');
  assert.equal(formatCurrency('not-a-number'), '₹0.00');
});

test('createBillService: getMyBills requests /patient/bills and validates response', async () => {
  const mockBills = [
    {
      id: 10,
      invoiceNumber: 'INV-20261115-001',
      amount: '500.00',
      currency: 'INR',
      status: 'UNPAID',
    },
    {
      id: 11,
      invoiceNumber: 'INV-20261115-002',
      amount: '750.00',
      currency: 'INR',
      status: 'PAID',
    },
  ];

  const mockClient = {
    async get(url, options) {
      assert.equal(url, '/patient/bills');
      return {
        headers: { 'content-type': 'application/json' },
        data: mockBills,
      };
    },
  };

  const service = createBillService(mockClient);
  const result = await service.getMyBills();
  assert.equal(result.length, 2);
  assert.equal(result[0].invoiceNumber, 'INV-20261115-001');
  assert.equal(result[1].status, 'PAID');
});

test('createBillService: getAllBills passes status query parameter', async () => {
  const mockClient = {
    async get(url, options) {
      assert.equal(url, '/admin/bills');
      assert.equal(options.params.status, 'UNPAID');
      return {
        headers: { 'content-type': 'application/json' },
        data: [
          {
            id: 20,
            invoiceNumber: 'INV-20261115-UNP',
            amount: '600.00',
            currency: 'INR',
            status: 'UNPAID',
          },
        ],
      };
    },
  };

  const service = createBillService(mockClient);
  const result = await service.getAllBills('UNPAID');
  assert.equal(result.length, 1);
  assert.equal(result[0].status, 'UNPAID');
});

test('createBillService: generateBill sends POST with appointmentId', async () => {
  const mockClient = {
    async post(url, body) {
      assert.equal(url, '/admin/bills/generate');
      assert.equal(body.appointmentId, 42);
      return {
        headers: { 'content-type': 'application/json' },
        data: {
          id: 30,
          invoiceNumber: 'INV-20261115-GEN',
          amount: '800.00',
          currency: 'INR',
          status: 'UNPAID',
        },
      };
    },
  };

  const service = createBillService(mockClient);
  const bill = await service.generateBill(42);
  assert.equal(bill.id, 30);
  assert.equal(bill.amount, '800.00');
});

test('createBillService: recordPayment sends paymentMethod and reference', async () => {
  const mockClient = {
    async post(url, body) {
      assert.equal(url, '/admin/bills/50/pay');
      assert.equal(body.paymentMethod, 'MANUAL_CASH');
      assert.equal(body.paymentReference, 'RCPT-001');
      assert.equal(body.notes, 'Desk cash');
      return {
        headers: { 'content-type': 'application/json' },
        data: {
          id: 50,
          invoiceNumber: 'INV-20261115-PAY',
          amount: '900.00',
          currency: 'INR',
          status: 'PAID',
        },
      };
    },
  };

  const service = createBillService(mockClient);
  const bill = await service.recordPayment(50, {
    paymentMethod: 'MANUAL_CASH',
    paymentReference: 'RCPT-001',
    notes: 'Desk cash',
  });
  assert.equal(bill.status, 'PAID');
});

test('createBillService: voidBill sends reason payload', async () => {
  const mockClient = {
    async post(url, body) {
      assert.equal(url, '/admin/bills/60/void');
      assert.equal(body.reason, 'Waiver granted');
      return {
        headers: { 'content-type': 'application/json' },
        data: {
          id: 60,
          invoiceNumber: 'INV-20261115-VOID',
          amount: '500.00',
          currency: 'INR',
          status: 'VOID',
        },
      };
    },
  };

  const service = createBillService(mockClient);
  const bill = await service.voidBill(60, { reason: 'Waiver granted' });
  assert.equal(bill.status, 'VOID');
});

test('createBillService: throws UnexpectedApiResponse on invalid content-type or structure', async () => {
  const badClient = {
    async get() {
      return {
        headers: { 'content-type': 'text/html' },
        data: '<html>Error</html>',
      };
    },
  };

  const service = createBillService(badClient);
  await assert.rejects(
    async () => service.getMyBills(),
    (err) => err instanceof UnexpectedApiResponse,
  );
});

test('appointment ID input validation strictly rejects scientific notation, fractions, and non-positives', () => {
  const isValidApptId = (val) => {
    const raw = String(val || '').trim();
    if (!/^[1-9]\d*$/.test(raw)) return false;
    const num = Number(raw);
    return Number.isSafeInteger(num) && num > 0;
  };

  assert.equal(isValidApptId('1'), true);
  assert.equal(isValidApptId('17'), true);
  assert.equal(isValidApptId('1000'), true);

  // Scientific notation MUST fail
  assert.equal(isValidApptId('1e3'), false, '1e3 must be rejected');
  assert.equal(isValidApptId('2E5'), false, '2E5 must be rejected');

  // Fractions and decimals MUST fail
  assert.equal(isValidApptId('123.9'), false, '123.9 must be rejected');
  assert.equal(isValidApptId('1.0'), false, '1.0 must be rejected');

  // Non-positives and invalid inputs MUST fail
  assert.equal(isValidApptId('0'), false, '0 must be rejected');
  assert.equal(isValidApptId('-5'), false, '-5 must be rejected');
  assert.equal(isValidApptId(''), false, 'empty string must be rejected');
  assert.equal(isValidApptId('abc'), false, 'non-numeric must be rejected');
  assert.equal(isValidApptId(null), false, 'null must be rejected');
  assert.equal(isValidApptId(undefined), false, 'undefined must be rejected');
});

test('status filter predicate strictly guards active tab against mismatched records', () => {
  const filterBills = (bills, activeFilter) => {
    return bills.filter((b) => {
      if (activeFilter !== 'ALL' && b.status !== activeFilter) return false;
      return true;
    });
  };

  const sampleBills = [
    { id: 1, invoiceNumber: 'INV-1', status: 'UNPAID' },
    { id: 2, invoiceNumber: 'INV-2', status: 'PAID' },
    { id: 3, invoiceNumber: 'INV-3', status: 'VOID' },
  ];

  const paidOnly = filterBills(sampleBills, 'PAID');
  assert.equal(paidOnly.length, 1);
  assert.equal(paidOnly[0].status, 'PAID');

  const unpaidOnly = filterBills(sampleBills, 'UNPAID');
  assert.equal(unpaidOnly.length, 1);
  assert.equal(unpaidOnly[0].status, 'UNPAID');

  const all = filterBills(sampleBills, 'ALL');
  assert.equal(all.length, 3);
});

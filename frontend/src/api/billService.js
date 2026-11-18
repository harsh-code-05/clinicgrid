import api from './axios.js';
import { isApiId, requireJsonResponse } from './responseValidation.js';

export const BILL_STATUSES = ['UNPAID', 'PAID', 'VOID'];

export const isBill = (b) => isApiId(b?.id)
  && typeof b.invoiceNumber === 'string'
  && b.invoiceNumber.startsWith('INV-')
  && (typeof b.amount === 'number' || typeof b.amount === 'string')
  && b.currency === 'INR'
  && BILL_STATUSES.includes(b.status);

/**
 * Converts a decimal string or integer into BigInt integer paise without floating point arithmetic.
 */
export function toPaise(amount) {
  if (amount == null) return 0n;
  let str;
  if (typeof amount === 'number') {
    if (!Number.isFinite(amount)) return 0n;
    str = amount.toFixed(2);
  } else {
    str = String(amount).trim();
  }
  if (!str || !/^-?\d+(\.\d+)?$/.test(str)) return 0n;
  const isNeg = str.startsWith('-');
  const clean = isNeg ? str.slice(1) : str;
  const parts = clean.split('.');
  const wholePart = parts[0] || '0';
  const fracPart = (parts[1] || '').padEnd(2, '0').slice(0, 2);
  try {
    const whole = BigInt(wholePart);
    const frac = BigInt(fracPart);
    const paise = whole * 100n + frac;
    return isNeg ? -paise : paise;
  } catch {
    return 0n;
  }
}

/**
 * Formats a BigInt paise value as INR string (e.g. 75000n -> "₹750.00").
 */
export function formatPaise(paise) {
  const isNeg = paise < 0n;
  const abs = isNeg ? -paise : paise;
  const whole = abs / 100n;
  const frac = abs % 100n;
  const fracStr = frac < 10n ? `0${frac}` : String(frac);
  return `${isNeg ? '-' : ''}₹${whole}.${fracStr}`;
}

export function formatCurrency(amount) {
  return formatPaise(toPaise(amount));
}

export function createBillService(client) {
  return {
    async getMyBills(signal) {
      const response = await client.get('/patient/bills', { signal });
      return requireJsonResponse(response, (data) => Array.isArray(data) && data.every(isBill));
    },

    async getPatientBill(id, signal) {
      const response = await client.get(`/patient/bills/${id}`, { signal });
      return requireJsonResponse(response, isBill);
    },

    async getAllBills(status = null, signal) {
      const params = status ? { status } : {};
      const response = await client.get('/admin/bills', { params, signal });
      return requireJsonResponse(response, (data) => Array.isArray(data) && data.every(isBill));
    },

    async getAdminBill(id, signal) {
      const response = await client.get(`/admin/bills/${id}`, { signal });
      return requireJsonResponse(response, isBill);
    },

    async generateBill(appointmentId) {
      const response = await client.post('/admin/bills/generate', { appointmentId });
      return requireJsonResponse(response, isBill);
    },

    async recordPayment(billId, { paymentMethod = 'MANUAL_DEMO', paymentReference = '', notes = '' } = {}) {
      const response = await client.post(`/admin/bills/${billId}/pay`, {
        paymentMethod,
        paymentReference,
        notes,
      });
      return requireJsonResponse(response, isBill);
    },

    async voidBill(billId, { reason }) {
      const response = await client.post(`/admin/bills/${billId}/void`, { reason });
      return requireJsonResponse(response, isBill);
    },
  };
}

export default createBillService(api);

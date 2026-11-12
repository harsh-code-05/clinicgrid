import { UnexpectedApiResponse } from '../api/responseValidation.js';

export const MAX_VERIFICATION_FILE_BYTES = 5 * 1024 * 1024;

export const DOCUMENT_KIND_CONFIG = {
  ID_PROOF: {
    kind: 'ID_PROOF',
    segment: 'id-proof',
    label: 'Government ID Proof',
    description: 'Clear PNG or JPEG photo of medical/government-issued identity card.',
  },
  QUALIFICATION: {
    kind: 'QUALIFICATION',
    segment: 'qualification',
    label: 'Medical Qualification Certificate',
    description: 'Clear PNG or JPEG scan of degree or medical council registration certificate.',
  },
};

export const VERIFICATION_STATUS_META = {
  UNVERIFIED: { label: 'Unverified Draft', pillClass: 'status-unverified' },
  SUBMITTED: { label: 'Submitted for Review', pillClass: 'status-submitted' },
  UNDER_REVIEW: { label: 'Under Review', pillClass: 'status-under-review' },
  VERIFIED: { label: 'Verified', pillClass: 'status-verified' },
  REJECTED: { label: 'Rejected — Action Required', pillClass: 'status-rejected' },
};

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function hasPngSignature(bytes) {
  if (bytes.length < PNG_SIGNATURE.length) return false;
  return PNG_SIGNATURE.every((expected, index) => bytes[index] === expected);
}

function hasJpegSignature(bytes) {
  return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

export async function validateVerificationImageFile(file) {
  if (!file) {
    return { valid: false, message: 'Select a PNG or JPEG image file to upload.' };
  }
  if (!Number.isFinite(file.size) || file.size <= 0) {
    return { valid: false, message: 'The selected verification file is empty.' };
  }
  if (file.size > MAX_VERIFICATION_FILE_BYTES) {
    return { valid: false, message: 'File exceeds the 5 MB (5 MiB) maximum allowed size.' };
  }

  const declaredType = typeof file.type === 'string' ? file.type.trim().toLowerCase() : '';
  if (declaredType && declaredType !== 'image/png' && declaredType !== 'image/jpeg') {
    return { valid: false, message: 'Only PNG and JPEG images are supported for verification documents.' };
  }

  if (typeof file.slice === 'function') {
    const headerBuffer = await file.slice(0, 8).arrayBuffer();
    const bytes = new Uint8Array(headerBuffer);
    const detectedType = hasPngSignature(bytes) ? 'image/png' : (hasJpegSignature(bytes) ? 'image/jpeg' : '');
    if (!detectedType) {
      return { valid: false, message: 'Invalid image file. Upload a valid PNG or JPEG image.' };
    }
    if (declaredType && declaredType !== detectedType) {
      return { valid: false, message: 'File contents do not match the selected image type. Upload a valid PNG or JPEG image.' };
    }
  }

  return { valid: true, message: '' };
}

export function validateRegistrationCredentialsForm({ registrationNumber, medicalCouncil, registrationYear }, currentYear = new Date().getFullYear()) {
  const regNum = typeof registrationNumber === 'string' ? registrationNumber.trim() : '';
  const council = typeof medicalCouncil === 'string' ? medicalCouncil.trim() : '';
  const yearNumber = Number(registrationYear);

  if (!regNum) {
    return { valid: false, message: 'Registration number is required.' };
  }
  if (regNum.length > 100) {
    return { valid: false, message: 'Registration number must be at most 100 characters.' };
  }
  if (!council) {
    return { valid: false, message: 'Medical council is required.' };
  }
  if (council.length > 255) {
    return { valid: false, message: 'Medical council must be at most 255 characters.' };
  }
  if (!Number.isInteger(yearNumber) || yearNumber < 1000 || yearNumber > currentYear) {
    return { valid: false, message: `Registration year must be a valid year between 1000 and ${currentYear}.` };
  }

  return {
    valid: true,
    message: '',
    payload: {
      registrationNumber: regNum,
      medicalCouncil: council,
      registrationYear: yearNumber,
    },
  };
}

function sanitizeErrorMessage(value) {
  if (typeof value !== 'string') return '';
  const trimmed = value.trim();
  if (!trimmed) return '';
  if (/^\s*</.test(trimmed) || /<(?:!doctype|html|head|body|title|h[1-6]|p|div|span|pre|script)\b/i.test(trimmed)) {
    return '';
  }
  return trimmed;
}

function normalizeFieldErrors(rawErrors) {
  if (!rawErrors || typeof rawErrors !== 'object' || Array.isArray(rawErrors)) {
    return null;
  }
  const entries = Object.entries(rawErrors)
    .map(([field, msg]) => [field, sanitizeErrorMessage(msg)])
    .filter(([, msg]) => Boolean(msg));
  return entries.length > 0 ? Object.fromEntries(entries) : null;
}

function extractParsedPayloadError(status, parsed) {
  if (typeof parsed === 'string') {
    return { status, code: null, message: sanitizeErrorMessage(parsed), errors: null };
  }
  if (!parsed || typeof parsed !== 'object') {
    return { status, code: null, message: '', errors: null };
  }
  return {
    status,
    code: typeof parsed.code === 'string' ? parsed.code : null,
    message: sanitizeErrorMessage(parsed.message),
    errors: normalizeFieldErrors(parsed.errors),
  };
}

export async function parseApiErrorDetails(error) {
  if (error instanceof UnexpectedApiResponse) {
    return { status: null, code: null, message: error.message, errors: null };
  }

  const status = error?.response?.status ?? null;
  const rawData = error?.response?.data;
  if (typeof Blob !== 'undefined' && rawData instanceof Blob && typeof rawData.text === 'function') {
    try {
      const text = await rawData.text();
      const parsed = JSON.parse(text);
      return extractParsedPayloadError(status, parsed);
    } catch {
      return { status, code: null, message: '', errors: null };
    }
  }

  return extractParsedPayloadError(status, rawData);
}

export function formatVerificationError(details, context = 'action') {
  const { status, code, message, errors } = details || {};
  const fieldSummary = errors ? Object.values(errors).join(' ') : '';

  if (status === 400) {
    if (fieldSummary) {
      return fieldSummary;
    }
    return message || 'Invalid verification request. Check your inputs and try again.';
  }
  if (status === 401) {
    return 'Your session has expired. Please sign in again to continue.';
  }
  if (status === 403) {
    return message || 'You do not have permission to perform this verification action.';
  }
  if (status === 409) {
    if (code === 'STALE_REVIEW_VERSION') {
      return message || 'Verification details changed before your decision. The latest state has been refreshed.';
    }
    if (context === 'preview') {
      return message || 'Evidence can only be previewed while the doctor is in UNDER_REVIEW status.';
    }
    return message || 'This action conflicts with the current verification status. Refresh to see the latest state.';
  }
  if (status === 413) {
    return message || 'Uploaded file exceeds the 5 MB (5 MiB) maximum allowed size.';
  }
  if (status === 415) {
    return message || 'Unsupported media type. Only PNG and JPEG images are allowed.';
  }
  if (status === 500 && code === 'DOCUMENT_INTEGRITY_ERROR') {
    return message || 'Verification document could not be verified. Please contact an administrator.';
  }
  if (status === 503) {
    return 'Private document storage is temporarily unavailable on this server (503). An administrator must configure hms.document-storage before verification documents can be stored or retrieved.';
  }

  return fieldSummary || message || 'Verification request failed. Please try again.';
}

export function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return 'N/A';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function formatVerificationTimestamp(value) {
  if (!value) return 'N/A';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function isDraftVerificationStatus(status) {
  return status === 'UNVERIFIED' || status === 'REJECTED';
}

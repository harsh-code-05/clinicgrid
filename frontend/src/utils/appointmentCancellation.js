export const MAX_CANCELLATION_REASON_LENGTH = 500;

function isCancellationWhitespace(codePoint) {
  if (codePoint === 0x0085 || (codePoint >= 0x001c && codePoint <= 0x001f)) {
    return true;
  }
  return /\s|\uFEFF/.test(String.fromCodePoint(codePoint));
}

export function normalizeCancellationReasonInput(reason) {
  if (typeof reason !== 'string') {
    return '';
  }
  let start = 0;
  let end = reason.length;
  while (start < end) {
    const codePoint = reason.codePointAt(start);
    if (!isCancellationWhitespace(codePoint)) {
      break;
    }
    start += codePoint > 0xffff ? 2 : 1;
  }
  while (end > start) {
    const unit = reason.charCodeAt(end - 1);
    const step = (unit >= 0xdc00 && unit <= 0xdfff && end - 2 >= start
      && reason.charCodeAt(end - 2) >= 0xd800 && reason.charCodeAt(end - 2) <= 0xdbff) ? 2 : 1;
    const codePoint = reason.codePointAt(end - step);
    if (!isCancellationWhitespace(codePoint)) {
      break;
    }
    end -= step;
  }
  return reason.slice(start, end);
}

export function buildCancelRequestPayload(reason) {
  const trimmed = normalizeCancellationReasonInput(reason);
  return { reason: trimmed.length > 0 ? trimmed : null };
}

export function validateCancellationReason(reason) {
  if (typeof reason !== 'string') {
    return '';
  }
  if (reason.includes('\u0000')) {
    return 'Cancellation reason cannot contain NUL characters.';
  }
  if (normalizeCancellationReasonInput(reason).length > MAX_CANCELLATION_REASON_LENGTH) {
    return `Cancellation reason cannot exceed ${MAX_CANCELLATION_REASON_LENGTH} characters.`;
  }
  return '';
}

export function formatCancellationNotice(appointment) {
  const id = appointment?.id;
  switch (appointment?.cancellationSlotOutcome) {
    case 'RELEASED':
      return `Appointment #${id} was cancelled and the time slot was released.`;
    case 'RETAINED_UNAVAILABLE':
      return `Appointment #${id} was cancelled. This slot belongs to an older schedule revision and remains withdrawn until republished.`;
    case 'WITHHELD':
      return `Appointment #${id} was cancelled. The time slot was withheld rather than reopened automatically.`;
    case 'OVERLAP':
      return `Appointment #${id} was cancelled. The time slot remains blocked because another confirmed appointment overlaps that time.`;
    default:
      return `Appointment #${id} was successfully cancelled.`;
  }
}

export function formatCancelledByRole(role) {
  switch (role) {
    case 'PATIENT':
      return 'Patient';
    case 'DOCTOR':
      return 'Doctor';
    case 'ADMIN':
      return 'Administrator';
    default:
      return '';
  }
}

export function formatSlotOutcomeLabel(outcome) {
  switch (outcome) {
    case 'RELEASED':
      return 'Slot released';
    case 'RETAINED_UNAVAILABLE':
      return 'Slot kept withdrawn (schedule updated)';
    case 'WITHHELD':
      return 'Slot withheld';
    case 'OVERLAP':
      return 'Slot blocked by overlapping booking';
    default:
      return '';
  }
}

export function formatCancelledAt(isoTimestamp) {
  if (typeof isoTimestamp !== 'string' || !isoTimestamp.trim()) {
    return '';
  }
  const millis = Date.parse(isoTimestamp);
  if (!Number.isFinite(millis)) {
    return '';
  }
  try {
    return new Date(millis).toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  } catch {
    return isoTimestamp;
  }
}

export function shouldRefreshAfterCancelError(error) {
  return error?.response?.status === 409;
}

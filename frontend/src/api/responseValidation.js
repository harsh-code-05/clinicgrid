export class UnexpectedApiResponse extends Error {
  constructor() {
    super('The server returned an unexpected response. Refresh to check whether the action took effect.');
  }
}

export const isApiId = (value) => Number.isSafeInteger(value) && value > 0;
const isText = (value) => typeof value === 'string' && value.trim().length > 0;
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
export function isDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value;
}
const isTime = (value) => typeof value === 'string'
  && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d+)?)?$/.test(value);

export function requireJsonResponse(response, matches) {
  const contentType = response?.headers?.get?.('content-type') ?? response?.headers?.['content-type'];
  const mediaType = typeof contentType === 'string' ? contentType.split(';', 1)[0].trim().toLowerCase() : '';
  if (!mediaType || (mediaType !== 'application/json' && !mediaType.endsWith('+json'))
      || !matches(response.data)) {
    throw new UnexpectedApiResponse();
  }
  return response.data;
}

export const CANCELLATION_ROLES = ['PATIENT', 'DOCTOR', 'ADMIN'];
export const CANCELLATION_SLOT_OUTCOMES = ['RELEASED', 'WITHHELD', 'RETAINED_UNAVAILABLE', 'OVERLAP'];

function isOptionalCancellationMetadataValid(data) {
  const reasonValid = data.cancellationReason == null
    || (typeof data.cancellationReason === 'string' && data.cancellationReason.length <= 500);
  const cancelledAtValid = data.cancelledAt == null
    || (typeof data.cancelledAt === 'string' && Number.isFinite(Date.parse(data.cancelledAt)));
  const cancelledByRoleValid = data.cancelledByRole == null
    || CANCELLATION_ROLES.includes(data.cancelledByRole);
  const slotOutcomeValid = data.cancellationSlotOutcome == null
    || CANCELLATION_SLOT_OUTCOMES.includes(data.cancellationSlotOutcome);
  return reasonValid && cancelledAtValid && cancelledByRoleValid && slotOutcomeValid;
}

export function requireAppointmentResult(response, status, matches) {
  return requireJsonResponse(response, (data) =>
    isObject(data) && isApiId(data.id) && data.status === status
    && isOptionalCancellationMetadataValid(data)
    && matches(data),
  );
}

export function requireAppointmentsList(response) {
  return requireJsonResponse(response, (data) =>
    Array.isArray(data)
    && data.every((item) =>
      isObject(item)
      && isApiId(item.id)
      && isOptionalCancellationMetadataValid(item)),
  );
}

export function requirePatientProfileResult(response, userId) {
  return requireJsonResponse(response, (data) =>
    isObject(data) && isApiId(data.id) && data.user?.id === userId
    && isText(data.firstName) && isText(data.lastName)
    && isDate(data.dateOfBirth) && isText(data.gender) && isText(data.phone),
  );
}

function isScheduleInterval(data) {
  return isObject(data) && isApiId(data.id) && isApiId(data.doctor?.id)
    && isDate(data.date) && isTime(data.startTime) && isTime(data.endTime);
}

export function requireAvailabilityResult(response, request) {
  return requireJsonResponse(response, (data) => isScheduleInterval(data)
    && data.doctor.id === request.doctorId && data.date === request.date);
}

export function requireAvailableSlots(response, doctorId, date) {
  return requireJsonResponse(response, (data) => Array.isArray(data)
    && data.every((slot) => isScheduleInterval(slot) && slot.status === 'AVAILABLE'
      && slot.doctor.id === doctorId && slot.date === date));
}

export function requireSlotGenerationResult(response, availability) {
  return requireAvailableSlots(response, availability.doctor.id, availability.date);
}

export const VERIFICATION_STATUSES = ['UNVERIFIED', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED'];
export const ADMIN_QUEUE_STATUSES = ['UNVERIFIED', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED'];
export const VERIFICATION_DOCUMENT_KINDS = ['ID_PROOF', 'QUALIFICATION'];
export const VERIFICATION_IMAGE_MIME_TYPES = ['image/png', 'image/jpeg'];

function extractMediaType(headers) {
  const contentType = headers?.get?.('content-type') ?? headers?.['content-type'];
  return typeof contentType === 'string' ? contentType.split(';', 1)[0].trim().toLowerCase() : '';
}

function isVerificationDocumentItem(doc, expectedDoctorId, expectedKind) {
  return isObject(doc)
    && isApiId(doc.doctorId)
    && (expectedDoctorId == null || doc.doctorId === expectedDoctorId)
    && VERIFICATION_DOCUMENT_KINDS.includes(doc.documentKind)
    && (expectedKind == null || doc.documentKind === expectedKind)
    && VERIFICATION_IMAGE_MIME_TYPES.includes(doc.mediaType)
    && Number.isSafeInteger(doc.fileSize) && doc.fileSize > 0
    && isText(doc.createdAt);
}

export function requireNoContentResponse(response) {
  if (response?.status !== 204) {
    throw new UnexpectedApiResponse();
  }
}

export function requireDoctorVerificationDetails(response, expectedDoctorId) {
  if (response?.status !== 200) {
    throw new UnexpectedApiResponse();
  }
  return requireJsonResponse(response, (data) =>
    isObject(data)
    && isApiId(data.doctorId)
    && (expectedDoctorId == null || data.doctorId === expectedDoctorId)
    && VERIFICATION_STATUSES.includes(data.status)
    && (data.latestRejectionReason == null || typeof data.latestRejectionReason === 'string')
    && isText(data.reviewVersion)
    && Array.isArray(data.history)
    && data.history.every((entry) =>
      isObject(entry)
      && VERIFICATION_STATUSES.includes(entry.previousStatus)
      && VERIFICATION_STATUSES.includes(entry.newStatus)
      && ['DOCTOR', 'ADMIN', 'SYSTEM'].includes(entry.actorRole)
      && isText(entry.occurredAt)),
  );
}

export function requireVerificationDocuments(response, expectedDoctorId) {
  if (response?.status !== 200) {
    throw new UnexpectedApiResponse();
  }
  return requireJsonResponse(response, (data) =>
    Array.isArray(data) && data.every((doc) => isVerificationDocumentItem(doc, expectedDoctorId, null)),
  );
}

export function requireUploadedVerificationDocument(response, expectedDoctorId, expectedKind) {
  if (response?.status !== 201) {
    throw new UnexpectedApiResponse();
  }
  return requireJsonResponse(response, (data) => isVerificationDocumentItem(data, expectedDoctorId, expectedKind));
}

export function requireVerificationQueue(response) {
  if (response?.status !== 200) {
    throw new UnexpectedApiResponse();
  }
  return requireJsonResponse(response, (data) =>
    isObject(data)
    && Array.isArray(data.items)
    && Number.isSafeInteger(data.page) && data.page >= 0
    && Number.isSafeInteger(data.size) && data.size >= 1
    && Number.isSafeInteger(data.totalElements) && data.totalElements >= 0
    && Number.isSafeInteger(data.totalPages) && data.totalPages >= 0
    && typeof data.hasNext === 'boolean'
    && typeof data.hasPrevious === 'boolean'
    && data.items.every((item) =>
      isObject(item)
      && isApiId(item.doctorId)
      && isText(item.doctorName)
      && ADMIN_QUEUE_STATUSES.includes(item.status)
      && (item.latestRejectionReason == null || typeof item.latestRejectionReason === 'string')),
  );
}

export function requirePreviewImageBlob(response) {
  if (response?.status !== 200) {
    throw new UnexpectedApiResponse();
  }
  const mediaType = extractMediaType(response?.headers);
  const blob = response?.data;
  const blobType = typeof blob?.type === 'string' ? blob.type.split(';', 1)[0].trim().toLowerCase() : '';
  const hasBlob = typeof Blob !== 'undefined' ? blob instanceof Blob : isObject(blob);
  const hasBytes = Number.isFinite(blob?.size) ? blob.size > 0 : true;
  if (!VERIFICATION_IMAGE_MIME_TYPES.includes(mediaType)
      || !hasBlob
      || !hasBytes
      || (blobType && !VERIFICATION_IMAGE_MIME_TYPES.includes(blobType))) {
    throw new UnexpectedApiResponse();
  }
  return blob;
}

export function mutationErrorMessage(error, fallback) {
  if (error instanceof UnexpectedApiResponse) return error.message;
  return error.response?.data?.message || fallback;
}

import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../api/axios';
import {
  requireDoctorVerificationDetails,
  requireNoContentResponse,
  requireUploadedVerificationDocument,
  requireVerificationDocuments,
} from '../api/responseValidation';
import {
  DOCUMENT_KIND_CONFIG,
  VERIFICATION_STATUS_META,
  formatFileSize,
  formatVerificationError,
  formatVerificationTimestamp,
  isDraftVerificationStatus,
  parseApiErrorDetails,
  validateRegistrationCredentialsForm,
  validateVerificationImageFile,
} from '../utils/doctorVerification';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import './VerificationPanels.css';

const DOCUMENT_KINDS = ['ID_PROOF', 'QUALIFICATION'];

export default function DoctorVerificationPanel({ doctorProfile, onVerificationChange }) {
  return (
    <DoctorVerificationPanelContent
      key={doctorProfile?.id ?? 'none'}
      doctorProfile={doctorProfile}
      onVerificationChange={onVerificationChange}
    />
  );
}

function DoctorVerificationPanelContent({ doctorProfile, onVerificationChange }) {
  const doctorId = doctorProfile?.id;

  const [verification, setVerification] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [registrationNumber, setRegistrationNumber] = useState('');
  const [medicalCouncil, setMedicalCouncil] = useState('');
  const [registrationYear, setRegistrationYear] = useState('');
  const [credentialsDirty, setCredentialsDirty] = useState(false);

  const [savingCredentials, setSavingCredentials] = useState(false);
  const [credentialsError, setCredentialsError] = useState('');
  const [credentialsSuccess, setCredentialsSuccess] = useState('');

  const [uploadingKind, setUploadingKind] = useState(null);
  const [removingKind, setRemovingKind] = useState(null);
  const [documentError, setDocumentError] = useState('');
  const [documentSuccess, setDocumentSuccess] = useState('');
  const [storageUnavailableNotice, setStorageUnavailableNotice] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [submitSuccess, setSubmitSuccess] = useState('');

  const sequencerRef = useRef(null);
  const mountedRef = useRef(true);
  const activeDoctorIdRef = useRef(doctorId);
  const loadGenerationRef = useRef(0);
  const credentialsDirtyRef = useRef(false);
  const submitInFlightRef = useRef(false);
  const documentInFlightRef = useRef(false);

  useEffect(() => {
    credentialsDirtyRef.current = credentialsDirty;
  }, [credentialsDirty]);

  const applyVerificationSnapshot = useCallback((details, activeDocs, { forceFormSync = false } = {}) => {
    setVerification(details);
    setDocuments(activeDocs);
    if (forceFormSync || !credentialsDirtyRef.current) {
      setRegistrationNumber(details.registrationNumber ?? '');
      setMedicalCouncil(details.medicalCouncil ?? '');
      setRegistrationYear(details.registrationYear != null ? String(details.registrationYear) : '');
      credentialsDirtyRef.current = false;
      setCredentialsDirty(false);
    }
  }, []);

  const loadVerificationState = useCallback(async ({ forceFormSync = false } = {}) => {
    if (!doctorId) return false;
    const targetDoctorId = doctorId;
    const targetGen = ++loadGenerationRef.current;
    if (!sequencerRef.current) {
      sequencerRef.current = new RequestSequencer();
      sequencerRef.current.mount();
    }

    const isCurrentLoad = () =>
      mountedRef.current
      && activeDoctorIdRef.current === targetDoctorId
      && loadGenerationRef.current === targetGen;

    return executeSequencedRequest(
      sequencerRef.current,
      async (signal) => {
        const [detailsRes, docsRes] = await Promise.all([
          api.get(`/doctors/${targetDoctorId}/verification`, { signal }),
          api.get(`/doctors/${targetDoctorId}/verification/documents`, { signal }),
        ]);
        const details = requireDoctorVerificationDetails(detailsRes, targetDoctorId);
        const activeDocs = requireVerificationDocuments(docsRes, targetDoctorId);
        return { details, activeDocs };
      },
      {
        onStart: () => {
          if (!isCurrentLoad()) return;
          setLoading(true);
          setLoadError('');
        },
        onSuccess: ({ details, activeDocs }) => {
          if (!isCurrentLoad()) return;
          applyVerificationSnapshot(details, activeDocs, { forceFormSync });
          setLoading(false);
        },
        onError: async (err) => {
          const parsed = await parseApiErrorDetails(err);
          if (!isCurrentLoad()) return;
          setLoadError(formatVerificationError(parsed, 'load'));
          setLoading(false);
        },
      },
    );
  }, [doctorId, applyVerificationSnapshot]);

  useEffect(() => {
    mountedRef.current = true;
    activeDoctorIdRef.current = doctorId;
    credentialsDirtyRef.current = false;

    const seq = new RequestSequencer();
    seq.mount();
    sequencerRef.current = seq;
    loadVerificationState({ forceFormSync: true });
    return () => {
      mountedRef.current = false;
      seq.dispose();
    };
  }, [doctorId, loadVerificationState]);

  const activeDocByKind = (kind) => documents.find((doc) => doc.documentKind === kind) || null;
  const idProofDoc = activeDocByKind('ID_PROOF');
  const qualificationDoc = activeDocByKind('QUALIFICATION');
  const hasBothDocuments = Boolean(idProofDoc && qualificationDoc);
  const status = verification?.status ?? 'UNVERIFIED';
  const statusMeta = VERIFICATION_STATUS_META[status] ?? { label: status, pillClass: 'status-unverified' };
  const isDraft = isDraftVerificationStatus(status);
  const hasSavedCredentials = Boolean(
    verification?.registrationNumber
    && verification?.medicalCouncil
    && verification?.registrationYear,
  );
  const busyWithAnyAction = savingCredentials || Boolean(uploadingKind) || Boolean(removingKind) || submitting;

  const handleSaveCredentials = async (event) => {
    event.preventDefault();
    const targetDoctorId = doctorId;
    if (!targetDoctorId || !isDraft || savingCredentials || submitInFlightRef.current || documentInFlightRef.current) {
      return;
    }

    setCredentialsError('');
    setCredentialsSuccess('');
    setSubmitError('');

    const validation = validateRegistrationCredentialsForm({
      registrationNumber,
      medicalCouncil,
      registrationYear,
    });
    if (!validation.valid) {
      setCredentialsError(validation.message);
      return;
    }

    if (!hasBothDocuments) {
      setCredentialsError('Upload both your Government ID Proof and Medical Qualification Certificate before saving registration credentials.');
      return;
    }

    setSavingCredentials(true);
    try {
      const response = await api.post(`/doctors/${targetDoctorId}/verification/credentials`, validation.payload);
      requireNoContentResponse(response);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      await loadVerificationState({ forceFormSync: true });
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setCredentialsSuccess('Registration credentials saved.');
      onVerificationChange?.();
    } catch (err) {
      const parsed = await parseApiErrorDetails(err);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setCredentialsError(formatVerificationError(parsed, 'credentials'));
      if (parsed.status === 409) {
        await loadVerificationState({ forceFormSync: false });
      }
    } finally {
      if (mountedRef.current) {
        setSavingCredentials(false);
      }
    }
  };

  const handleFileUpload = async (kind, file) => {
    const targetDoctorId = doctorId;
    if (!targetDoctorId || !isDraft || documentInFlightRef.current || submitInFlightRef.current || savingCredentials) {
      return;
    }
    if (!file) return;

    const kindMeta = DOCUMENT_KIND_CONFIG[kind];
    setDocumentError('');
    setDocumentSuccess('');
    setStorageUnavailableNotice('');
    setSubmitError('');

    documentInFlightRef.current = true;
    setUploadingKind(kind);
    try {
      const validation = await validateVerificationImageFile(file);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      if (!validation.valid) {
        setDocumentError(`${kindMeta.label}: ${validation.message}`);
        return;
      }

      const formData = new FormData();
      formData.append('file', file);
      const response = await api.postForm(
        `/doctors/${targetDoctorId}/verification/documents/${kindMeta.segment}`,
        formData,
      );
      const uploaded = requireUploadedVerificationDocument(response, targetDoctorId, kind);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setDocumentSuccess(`${kindMeta.label} uploaded (${uploaded.mediaType}, ${formatFileSize(uploaded.fileSize)}).`);
      await loadVerificationState();
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      onVerificationChange?.();
    } catch (err) {
      const parsed = await parseApiErrorDetails(err);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      const message = formatVerificationError(parsed, 'upload');
      if (parsed.status === 503) {
        setStorageUnavailableNotice(message);
      } else {
        setDocumentError(`${kindMeta.label}: ${message}`);
      }
      if (parsed.status === 409) {
        await loadVerificationState({ forceFormSync: false });
      }
    } finally {
      documentInFlightRef.current = false;
      if (mountedRef.current) {
        setUploadingKind(null);
      }
    }
  };

  const handleDetachDocument = async (kind) => {
    const targetDoctorId = doctorId;
    if (!targetDoctorId || !isDraft || documentInFlightRef.current || submitInFlightRef.current || savingCredentials) {
      return;
    }

    const kindMeta = DOCUMENT_KIND_CONFIG[kind];
    setDocumentError('');
    setDocumentSuccess('');
    setStorageUnavailableNotice('');
    setSubmitError('');

    documentInFlightRef.current = true;
    setRemovingKind(kind);
    try {
      const response = await api.delete(`/doctors/${targetDoctorId}/verification/documents/${kindMeta.segment}`);
      requireNoContentResponse(response);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setDocumentSuccess(`${kindMeta.label} removed.`);
      await loadVerificationState();
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      onVerificationChange?.();
    } catch (err) {
      const parsed = await parseApiErrorDetails(err);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setDocumentError(`${kindMeta.label}: ${formatVerificationError(parsed, 'detach')}`);
      if (parsed.status === 409) {
        await loadVerificationState({ forceFormSync: false });
      }
    } finally {
      documentInFlightRef.current = false;
      if (mountedRef.current) {
        setRemovingKind(null);
      }
    }
  };

  const handleSubmitVerification = async () => {
    const targetDoctorId = doctorId;
    if (!targetDoctorId || !isDraft || submitInFlightRef.current || documentInFlightRef.current || savingCredentials) {
      return;
    }

    setSubmitError('');
    setSubmitSuccess('');

    if (!hasBothDocuments) {
      setSubmitError('Upload both your Government ID Proof and Medical Qualification Certificate before submitting.');
      return;
    }
    if (credentialsDirty) {
      setSubmitError('Save your updated registration credentials before submitting for verification.');
      return;
    }
    if (!hasSavedCredentials) {
      setSubmitError('Complete and save your registration credentials before submitting for verification.');
      return;
    }

    submitInFlightRef.current = true;
    setSubmitting(true);
    try {
      const response = await api.post(`/doctors/${targetDoctorId}/verification/submit`);
      requireNoContentResponse(response);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      await loadVerificationState({ forceFormSync: true });
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setSubmitSuccess('Your verification dossier has been submitted for administrative review.');
      onVerificationChange?.();
    } catch (err) {
      const parsed = await parseApiErrorDetails(err);
      if (!mountedRef.current || activeDoctorIdRef.current !== targetDoctorId) return;
      setSubmitError(formatVerificationError(parsed, 'submit'));
      if (parsed.status === 409) {
        await loadVerificationState({ forceFormSync: false });
      }
    } finally {
      submitInFlightRef.current = false;
      if (mountedRef.current) {
        setSubmitting(false);
      }
    }
  };

  if (!doctorId) {
    return (
      <div className="dashboard-alert alert-error" role="alert">
        Unable to resolve your Doctor Profile ID. Please refresh and try again.
      </div>
    );
  }

  const profileFullName = doctorProfile.firstName || doctorProfile.lastName
    ? `Dr. ${[doctorProfile.firstName, doctorProfile.lastName].filter(Boolean).join(' ')}`
    : `Doctor #${doctorId}`;

  return (
    <section className="verification-panel" aria-label="Doctor Professional Verification">
      {/* Header & Doctor Profile Summary */}
      <div className="verification-summary-card">
        <div className="verification-header-row">
          <div>
            <h2>Professional Verification</h2>
            <p className="verification-subtitle">
              Submit medical registration credentials and identity/qualification documents to receive your verified clinical badge.
            </p>
          </div>
          <div className="verification-action-bar">
            {verification && (
              <span className={`status-pill ${statusMeta.pillClass}`} data-testid="verification-status-badge">
                {statusMeta.label} ({status})
              </span>
            )}
            <button
              type="button"
              className="btn-secondary"
              onClick={() => loadVerificationState({ forceFormSync: false })}
              disabled={loading || busyWithAnyAction}
            >
              {loading ? 'Refreshing...' : 'Refresh Status'}
            </button>
          </div>
        </div>

        <div className="verification-profile-grid">
          <div className="verification-field-meta">
            <span className="verification-field-label">Doctor Profile</span>
            <span className="verification-field-value">
              {profileFullName} (#{doctorId})
            </span>
          </div>
          <div className="verification-field-meta">
            <span className="verification-field-label">Specialization</span>
            <span className="verification-field-value">
              {doctorProfile.specialization?.name || 'Not specified'}
            </span>
          </div>
          <div className="verification-field-meta">
            <span className="verification-field-label">Qualification</span>
            <span className="verification-field-value">
              {doctorProfile.qualification || 'Not specified'}
            </span>
          </div>
          <div className="verification-field-meta">
            <span className="verification-field-label">Experience</span>
            <span className="verification-field-value">
              {doctorProfile.experience != null ? `${doctorProfile.experience} years` : 'Not specified'}
            </span>
          </div>
        </div>

        {/* Status-Specific Guidance & Rejection Reason Banners */}
        {verification && status === 'REJECTED' && (
          <div className="dashboard-alert alert-error" role="alert">
            <div>
              <strong>Verification Rejected:</strong>{' '}
              {verification.latestRejectionReason
                ? verification.latestRejectionReason
                : 'Your verification submission was rejected by an administrator. Update your documents or registration credentials and resubmit.'}
            </div>
          </div>
        )}

        {verification && status === 'SUBMITTED' && (
          <div className="dashboard-alert alert-info" role="status">
            <div>
              <strong>Awaiting Review:</strong> Your credentials and documents have been submitted and are queued for administrative review. Editing is locked while submitted.
            </div>
          </div>
        )}

        {verification && status === 'UNDER_REVIEW' && (
          <div className="dashboard-alert alert-warning" role="status">
            <div>
              <strong>Under Administrative Review:</strong> An administrator is currently inspecting your dossier. Credentials and documents cannot be changed during review.
            </div>
          </div>
        )}

        {verification && status === 'VERIFIED' && (
          <div className="dashboard-alert alert-success" role="status">
            <div>
              <strong>Verified Practitioner:</strong> Your professional verification is complete. Verified doctors are eligible for patient discovery and clinical bookings.
            </div>
          </div>
        )}
      </div>

      {loadError && (
        <div className="dashboard-alert alert-error" role="alert">
          <div>{loadError}</div>
        </div>
      )}

      {loading && !verification ? (
        <div className="dashboard-alert alert-warning" role="status">
          Loading professional verification details...
        </div>
      ) : verification ? (
        <>
          {/* Step 1: Verification Documents */}
          <div className="verification-card">
            <div className="verification-header-row">
              <h3>1. Private Verification Documents</h3>
              <span className="verification-subtitle">Accepted formats: PNG or JPEG, max 5 MB each</span>
            </div>

            {storageUnavailableNotice && (
              <div className="dashboard-alert alert-warning" role="alert">
                <div>{storageUnavailableNotice}</div>
              </div>
            )}

            {documentError && (
              <div className="dashboard-alert alert-error" role="alert">
                <div>{documentError}</div>
              </div>
            )}

            {documentSuccess && (
              <div className="dashboard-alert alert-success" role="status">
                <div>{documentSuccess}</div>
              </div>
            )}

            <div className="verification-documents-grid">
              {DOCUMENT_KINDS.map((kind) => {
                const kindMeta = DOCUMENT_KIND_CONFIG[kind];
                const doc = activeDocByKind(kind);
                const isUploadingThis = uploadingKind === kind;
                const isRemovingThis = removingKind === kind;
                const inputId = `doctor-doc-input-${kindMeta.segment}`;

                return (
                  <div key={kind} className="verification-doc-box">
                    <div className="verification-doc-box-header">
                      <h4>{kindMeta.label}</h4>
                      <span className={`status-pill ${doc ? 'status-verified' : 'status-unverified'}`}>
                        {doc ? 'Attached' : 'Missing'}
                      </span>
                    </div>
                    <p className="verification-subtitle">{kindMeta.description}</p>

                    {doc ? (
                      <div className="verification-doc-meta">
                        <div className="verification-field-meta">
                          <span className="verification-field-label">Media Type</span>
                          <span className="verification-field-value">{doc.mediaType}</span>
                        </div>
                        <div className="verification-field-meta">
                          <span className="verification-field-label">File Size</span>
                          <span className="verification-field-value">{formatFileSize(doc.fileSize)}</span>
                        </div>
                        <div className="verification-field-meta">
                          <span className="verification-field-label">Uploaded</span>
                          <span className="verification-field-value">{formatVerificationTimestamp(doc.createdAt)}</span>
                        </div>
                      </div>
                    ) : (
                      <div className="verification-subtitle">No active document attached yet.</div>
                    )}

                    {isDraft ? (
                      <div className="verification-doc-actions">
                        <label
                          htmlFor={inputId}
                          className={`verification-file-label ${busyWithAnyAction ? 'disabled' : ''}`}
                        >
                          {isUploadingThis ? 'Uploading...' : doc ? 'Replace Document' : 'Upload PNG/JPEG'}
                        </label>
                        <input
                          id={inputId}
                          type="file"
                          accept="image/png,image/jpeg"
                          className="verification-file-input"
                          disabled={busyWithAnyAction}
                          onChange={(e) => {
                            const selectedFile = e.target.files?.[0];
                            e.target.value = '';
                            if (selectedFile) {
                              handleFileUpload(kind, selectedFile);
                            }
                          }}
                        />
                        {doc && (
                          <button
                            type="button"
                            className="btn-cancel-appt"
                            onClick={() => handleDetachDocument(kind)}
                            disabled={busyWithAnyAction}
                          >
                            {isRemovingThis ? 'Removing...' : 'Remove Document'}
                          </button>
                        )}
                      </div>
                    ) : (
                      <div className="verification-subtitle">
                        Locked in {status} status (metadata only — private evidence is restricted to admin review).
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          {/* Step 2: Medical Registration Credentials */}
          <form className="verification-card" onSubmit={handleSaveCredentials} noValidate>
            <div className="verification-header-row">
              <h3>2. Medical Registration Credentials</h3>
              {!isDraft && <span className="verification-subtitle">Read-only in {status} status</span>}
            </div>

            {credentialsError && (
              <div className="dashboard-alert alert-error" role="alert">
                <div>{credentialsError}</div>
              </div>
            )}

            {credentialsSuccess && (
              <div className="dashboard-alert alert-success" role="status">
                <div>{credentialsSuccess}</div>
              </div>
            )}

            <div className="verification-form-grid">
              <div className="verification-form-field">
                <label htmlFor="verif-reg-number">Registration Number</label>
                <input
                  id="verif-reg-number"
                  type="text"
                  maxLength={100}
                  className="verification-input"
                  value={registrationNumber}
                  onChange={(e) => {
                    if (submitting || submitInFlightRef.current || savingCredentials) return;
                    setRegistrationNumber(e.target.value);
                    credentialsDirtyRef.current = true;
                    setCredentialsDirty(true);
                    setCredentialsSuccess('');
                  }}
                  placeholder="e.g. NMC-2020-48129"
                  disabled={!isDraft || busyWithAnyAction}
                />
              </div>

              <div className="verification-form-field">
                <label htmlFor="verif-med-council">Medical Council</label>
                <input
                  id="verif-med-council"
                  type="text"
                  maxLength={255}
                  className="verification-input"
                  value={medicalCouncil}
                  onChange={(e) => {
                    if (submitting || submitInFlightRef.current || savingCredentials) return;
                    setMedicalCouncil(e.target.value);
                    credentialsDirtyRef.current = true;
                    setCredentialsDirty(true);
                    setCredentialsSuccess('');
                  }}
                  placeholder="e.g. Uttar Pradesh Medical Council"
                  disabled={!isDraft || busyWithAnyAction}
                />
              </div>

              <div className="verification-form-field">
                <label htmlFor="verif-reg-year">Registration Year</label>
                <input
                  id="verif-reg-year"
                  type="number"
                  min={1000}
                  max={new Date().getFullYear()}
                  className="verification-input"
                  value={registrationYear}
                  onChange={(e) => {
                    if (submitting || submitInFlightRef.current || savingCredentials) return;
                    setRegistrationYear(e.target.value);
                    credentialsDirtyRef.current = true;
                    setCredentialsDirty(true);
                    setCredentialsSuccess('');
                  }}
                  placeholder="e.g. 2019"
                  disabled={!isDraft || busyWithAnyAction}
                />
              </div>
            </div>

            {isDraft && (
              <div className="verification-header-row">
                <span className="verification-subtitle">
                  {hasBothDocuments
                    ? 'Save registration details after updating any field.'
                    : 'Both verification documents must be uploaded before saving credentials on the server.'}
                </span>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={savingCredentials || submitting || Boolean(uploadingKind) || Boolean(removingKind)}
                >
                  {savingCredentials ? 'Saving Credentials...' : 'Save Registration Credentials'}
                </button>
              </div>
            )}
          </form>

          {/* Step 3: Submission Checklist & Action */}
          <div className="verification-card">
            <div className="verification-header-row">
              <h3>3. Submit Verification Dossier</h3>
            </div>

            {submitError && (
              <div className="dashboard-alert alert-error" role="alert">
                <div>{submitError}</div>
              </div>
            )}

            {submitSuccess && (
              <div className="dashboard-alert alert-success" role="status">
                <div>{submitSuccess}</div>
              </div>
            )}

            <ul className="verification-checklist">
              <li>
                <span className={idProofDoc ? 'check-ok' : 'check-missing'}>
                  {idProofDoc ? '✓' : '○'}
                </span>
                <span>Government ID Proof document attached</span>
              </li>
              <li>
                <span className={qualificationDoc ? 'check-ok' : 'check-missing'}>
                  {qualificationDoc ? '✓' : '○'}
                </span>
                <span>Medical Qualification Certificate attached</span>
              </li>
              <li>
                <span className={hasSavedCredentials && !credentialsDirty ? 'check-ok' : 'check-missing'}>
                  {hasSavedCredentials && !credentialsDirty ? '✓' : '○'}
                </span>
                <span>
                  Registration credentials saved
                  {credentialsDirty ? ' (unsaved changes detected — click Save Registration Credentials first)' : ''}
                </span>
              </li>
            </ul>

            {isDraft && (
              <div className="verification-header-row">
                <span className="verification-subtitle">
                  {status === 'REJECTED'
                    ? 'Ready to resubmit after addressing the feedback above.'
                    : 'Once submitted, your dossier is locked until administrative review finishes.'}
                </span>
                <button
                  type="button"
                  className="btn-confirm-complete"
                  onClick={handleSubmitVerification}
                  disabled={
                    submitting
                    || savingCredentials
                    || Boolean(uploadingKind)
                    || Boolean(removingKind)
                    || !hasBothDocuments
                    || !hasSavedCredentials
                    || credentialsDirty
                  }
                >
                  {submitting
                    ? 'Submitting...'
                    : status === 'REJECTED'
                    ? 'Resubmit for Verification'
                    : 'Submit for Verification'}
                </button>
              </div>
            )}
          </div>

          {/* Step 4: Verification History */}
          <div className="verification-card">
            <div className="verification-header-row">
              <h3>Verification History</h3>
              <span className="verification-subtitle">{verification.history.length} event(s)</span>
            </div>

            {verification.history.length === 0 ? (
              <p className="verification-subtitle">No verification transitions recorded yet.</p>
            ) : (
              <div className="verification-history-list">
                {verification.history.map((entry, idx) => (
                  <div key={`${entry.occurredAt}-${entry.newStatus}-${idx}`} className="verification-history-item">
                    <div>
                      <strong>{entry.previousStatus}</strong> → <strong>{entry.newStatus}</strong>{' '}
                      <span className="verification-subtitle">by {entry.actorRole}</span>
                    </div>
                    <span className="verification-subtitle">
                      {formatVerificationTimestamp(entry.occurredAt)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      ) : null}
    </section>
  );
}

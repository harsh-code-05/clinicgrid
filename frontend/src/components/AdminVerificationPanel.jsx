import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../api/axios';
import {
  requireDoctorVerificationDetails,
  requireNoContentResponse,
  requirePreviewImageBlob,
  requireVerificationDocuments,
  requireVerificationQueue,
} from '../api/responseValidation';
import {
  DOCUMENT_KIND_CONFIG,
  VERIFICATION_STATUS_META,
  formatFileSize,
  formatVerificationError,
  formatVerificationTimestamp,
  parseApiErrorDetails,
} from '../utils/doctorVerification';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import './VerificationPanels.css';

const QUEUE_FILTERS = [
  { key: 'ALL', label: 'All Submissions' },
  { key: 'SUBMITTED', label: 'Submitted' },
  { key: 'UNDER_REVIEW', label: 'Under Review' },
  { key: 'REJECTED', label: 'Rejected' },
  { key: 'VERIFIED', label: 'Verified' },
];

const QUEUE_PAGE_SIZE = 10;

function sameQueueQuery(a, b) {
  return Boolean(a && b)
    && a.statusFilter === b.statusFilter
    && a.page === b.page
    && a.size === b.size;
}

export default function AdminVerificationPanel() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [page, setPage] = useState(0);
  const [queueData, setQueueData] = useState({
    items: [],
    page: 0,
    size: QUEUE_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  });
  const [queueLoading, setQueueLoading] = useState(true);
  const [queueError, setQueueError] = useState('');

  const [selectedDoctorId, setSelectedDoctorId] = useState(null);
  const [selectedSummary, setSelectedSummary] = useState(null);
  const [detailData, setDetailData] = useState(null);
  const [detailDocuments, setDetailDocuments] = useState([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');

  const [previewKind, setPreviewKind] = useState(null);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');

  const [rejectionReason, setRejectionReason] = useState('');
  const [decisionBusy, setDecisionBusy] = useState(null);
  const [decisionError, setDecisionError] = useState('');
  const [decisionNotice, setDecisionNotice] = useState('');

  const mountedRef = useRef(true);
  const queueSequencerRef = useRef(null);
  const detailSequencerRef = useRef(null);
  const queueQueryRef = useRef({
    statusFilter: 'ALL',
    page: 0,
    size: QUEUE_PAGE_SIZE,
  });
  const queueGenerationRef = useRef(0);
  const selectionGenerationRef = useRef(0);
  const selectedDoctorIdRef = useRef(null);
  const previewAbortRef = useRef(null);
  const previewGenerationRef = useRef(0);
  const previewUrlRef = useRef(null);
  const decisionInFlightRef = useRef(false);

  const revokePreview = useCallback(() => {
    previewGenerationRef.current += 1;
    if (previewAbortRef.current) {
      previewAbortRef.current.abort();
      previewAbortRef.current = null;
    }
    if (previewUrlRef.current) {
      URL.revokeObjectURL(previewUrlRef.current);
      previewUrlRef.current = null;
    }
    if (mountedRef.current) {
      setPreviewUrl(null);
      setPreviewKind(null);
      setPreviewLoading(false);
    }
  }, []);

  const fetchQueue = useCallback(async (explicitQuery) => {
    const targetQuery = explicitQuery
      ? {
        statusFilter: explicitQuery.statusFilter ?? 'ALL',
        page: Math.max(0, explicitQuery.page ?? 0),
        size: explicitQuery.size ?? QUEUE_PAGE_SIZE,
      }
      : { ...queueQueryRef.current };

    const queueGen = ++queueGenerationRef.current;
    if (!queueSequencerRef.current) {
      queueSequencerRef.current = new RequestSequencer();
      queueSequencerRef.current.mount();
    }

    const isCurrentQueue = () =>
      mountedRef.current
      && queueGenerationRef.current === queueGen
      && sameQueueQuery(queueQueryRef.current, targetQuery);

    return executeSequencedRequest(
      queueSequencerRef.current,
      async (signal) => {
        const params = { page: targetQuery.page, size: targetQuery.size };
        if (targetQuery.statusFilter !== 'ALL') {
          params.status = targetQuery.statusFilter;
        }
        const res = await api.get('/admin/doctor-verifications', { params, signal });
        return requireVerificationQueue(res);
      },
      {
        onStart: () => {
          if (!isCurrentQueue()) return;
          setQueueLoading(true);
          setQueueError('');
        },
        onSuccess: (data) => {
          if (!isCurrentQueue()) return;
          setQueueData(data);
          setQueueLoading(false);
        },
        onError: async (err) => {
          const parsed = await parseApiErrorDetails(err);
          if (!isCurrentQueue()) return;
          setQueueData({
            items: [],
            page: targetQuery.page,
            size: targetQuery.size,
            totalElements: 0,
            totalPages: 0,
            hasNext: false,
            hasPrevious: false,
          });
          setQueueError(formatVerificationError(parsed, 'queue'));
          setQueueLoading(false);
        },
      },
    );
  }, []);

  const loadDoctorDetails = useCallback(async (doctorId, targetGen = selectionGenerationRef.current) => {
    if (!doctorId) return null;
    if (!detailSequencerRef.current) {
      detailSequencerRef.current = new RequestSequencer();
      detailSequencerRef.current.mount();
    }

    const isCurrentSelection = () =>
      mountedRef.current
      && selectionGenerationRef.current === targetGen
      && selectedDoctorIdRef.current === doctorId;

    return executeSequencedRequest(
      detailSequencerRef.current,
      async (signal) => {
        const [detailsRes, docsRes] = await Promise.all([
          api.get(`/doctors/${doctorId}/verification`, { signal }),
          api.get(`/doctors/${doctorId}/verification/documents`, { signal }),
        ]);
        return {
          details: requireDoctorVerificationDetails(detailsRes, doctorId),
          docs: requireVerificationDocuments(docsRes, doctorId),
        };
      },
      {
        onStart: () => {
          if (!isCurrentSelection()) return;
          setDetailLoading(true);
          setDetailError('');
        },
        onSuccess: ({ details, docs }) => {
          if (!isCurrentSelection()) return;
          setDetailData(details);
          setDetailDocuments(docs);
          setDetailLoading(false);
        },
        onError: async (err) => {
          const parsed = await parseApiErrorDetails(err);
          if (!isCurrentSelection()) return;
          setDetailError(formatVerificationError(parsed, 'details'));
          setDetailLoading(false);
        },
      },
    );
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    const queueSeq = new RequestSequencer();
    const detailSeq = new RequestSequencer();
    queueSeq.mount();
    detailSeq.mount();
    queueSequencerRef.current = queueSeq;
    detailSequencerRef.current = detailSeq;

    fetchQueue(queueQueryRef.current);

    return () => {
      mountedRef.current = false;
      queueGenerationRef.current += 1;
      selectionGenerationRef.current += 1;
      previewGenerationRef.current += 1;
      queueSeq.dispose();
      detailSeq.dispose();
      revokePreview();
    };
  }, [revokePreview, fetchQueue]);

  const handleFilterChange = (nextFilter) => {
    const nextQuery = {
      statusFilter: nextFilter,
      page: 0,
      size: queueQueryRef.current.size,
    };
    if (sameQueueQuery(queueQueryRef.current, nextQuery) && !queueError) {
      return;
    }
    queueQueryRef.current = nextQuery;
    setStatusFilter(nextQuery.statusFilter);
    setPage(nextQuery.page);
    setQueueError('');
    setQueueData((prev) => ({
      ...prev,
      items: [],
      page: nextQuery.page,
      hasNext: false,
      hasPrevious: false,
    }));
    fetchQueue(nextQuery);
  };

  const handlePageChange = (nextPage) => {
    const safePage = Math.max(0, nextPage);
    const nextQuery = {
      statusFilter: queueQueryRef.current.statusFilter,
      page: safePage,
      size: queueQueryRef.current.size,
    };
    if (sameQueueQuery(queueQueryRef.current, nextQuery) && !queueError) {
      return;
    }
    queueQueryRef.current = nextQuery;
    setPage(safePage);
    setQueueError('');
    setQueueData((prev) => ({
      ...prev,
      items: [],
      page: safePage,
      hasNext: false,
      hasPrevious: false,
    }));
    fetchQueue(nextQuery);
  };

  const handleSelectDoctor = async (item) => {
    const nextDoctorId = item.doctorId;
    const isDifferentDoctor = selectedDoctorIdRef.current !== nextDoctorId;
    const targetGen = selectionGenerationRef.current + 1;
    selectionGenerationRef.current = targetGen;
    selectedDoctorIdRef.current = nextDoctorId;

    revokePreview();
    setPreviewError('');
    setDecisionError('');
    setDecisionNotice('');
    setDetailError('');
    setDetailData(null);
    setDetailDocuments([]);
    if (isDifferentDoctor) {
      setRejectionReason('');
    }
    setSelectedDoctorId(nextDoctorId);
    setSelectedSummary(item);
    await loadDoctorDetails(nextDoctorId, targetGen);
  };

  const handlePreviewDocument = async (kind) => {
    const targetDoctorId = selectedDoctorIdRef.current;
    const targetGen = selectionGenerationRef.current;
    const reviewVersion = detailData?.reviewVersion;
    if (!targetDoctorId || detailData?.status !== 'UNDER_REVIEW' || !reviewVersion) return;

    const kindMeta = DOCUMENT_KIND_CONFIG[kind];
    revokePreview();
    const previewGen = ++previewGenerationRef.current;
    setPreviewError('');
    setPreviewKind(kind);
    setPreviewLoading(true);

    const controller = new AbortController();
    previewAbortRef.current = controller;

    const isActivePreview = () =>
      mountedRef.current
      && previewGenerationRef.current === previewGen
      && selectionGenerationRef.current === targetGen
      && selectedDoctorIdRef.current === targetDoctorId;

    try {
      const response = await api.get(
        `/doctors/${targetDoctorId}/verification/documents/${kindMeta.segment}`,
        { params: { reviewVersion }, responseType: 'blob', signal: controller.signal },
      );
      if (!isActivePreview() || previewAbortRef.current !== controller) return;

      const blob = requirePreviewImageBlob(response);
      if (!isActivePreview() || previewAbortRef.current !== controller) return;

      const nextUrl = URL.createObjectURL(blob);
      if (!isActivePreview() || previewAbortRef.current !== controller) {
        URL.revokeObjectURL(nextUrl);
        return;
      }
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
      }
      previewUrlRef.current = nextUrl;
      setPreviewUrl(nextUrl);
      setPreviewLoading(false);
    } catch (err) {
      if (err?.code === 'ERR_CANCELED' || err?.name === 'AbortError' || err?.name === 'CanceledError') {
        return;
      }
      const parsed = await parseApiErrorDetails(err);
      if (!isActivePreview()) return;

      setPreviewLoading(false);
      setPreviewKind(null);
      setPreviewError(formatVerificationError(parsed, 'preview'));
      if (parsed.status === 409) {
        revokePreview();
        await Promise.all([loadDoctorDetails(targetDoctorId, targetGen), fetchQueue()]);
      }
    } finally {
      if (previewAbortRef.current === controller) {
        previewAbortRef.current = null;
      }
    }
  };

  const runDecision = async (actionType, requestFn, successMessage) => {
    const targetDoctorId = selectedDoctorIdRef.current;
    const targetGen = selectionGenerationRef.current;
    if (!targetDoctorId || decisionInFlightRef.current) return;

    const isCurrentSelection = () =>
      mountedRef.current
      && selectionGenerationRef.current === targetGen
      && selectedDoctorIdRef.current === targetDoctorId;

    decisionInFlightRef.current = true;
    setDecisionBusy(actionType);
    setDecisionError('');
    setDecisionNotice('');

    try {
      const response = await requestFn(targetDoctorId);
      requireNoContentResponse(response);
      if (!isCurrentSelection()) return;

      revokePreview();
      if (actionType === 'reject') {
        setRejectionReason('');
      }
      setDecisionNotice(successMessage);
      await Promise.all([loadDoctorDetails(targetDoctorId, targetGen), fetchQueue()]);
    } catch (err) {
      const parsed = await parseApiErrorDetails(err);
      if (!isCurrentSelection()) return;

      setDecisionError(formatVerificationError(parsed, 'decision'));
      if (parsed.status === 409) {
        revokePreview();
        await Promise.all([loadDoctorDetails(targetDoctorId, targetGen), fetchQueue()]);
      }
    } finally {
      decisionInFlightRef.current = false;
      if (mountedRef.current) {
        setDecisionBusy(null);
      }
    }
  };

  const handleStartReview = () => {
    const targetDoctorId = selectedDoctorIdRef.current;
    const reviewVersion = detailData?.reviewVersion;
    if (!targetDoctorId || !reviewVersion) return;
    runDecision(
      'review',
      (doctorId) => api.post(`/doctors/${doctorId}/verification/review`, { reviewVersion }),
      `Doctor #${targetDoctorId} moved to UNDER_REVIEW. Private evidence preview is now unlocked.`,
    );
  };

  const handleApprove = () => {
    const targetDoctorId = selectedDoctorIdRef.current;
    const reviewVersion = detailData?.reviewVersion;
    if (!targetDoctorId || !reviewVersion) return;
    runDecision(
      'approve',
      (doctorId) => api.post(`/doctors/${doctorId}/verification/approve`, { reviewVersion }),
      `Doctor #${targetDoctorId} has been marked VERIFIED.`,
    );
  };

  const handleReject = () => {
    const targetDoctorId = selectedDoctorIdRef.current;
    const reviewVersion = detailData?.reviewVersion;
    if (!targetDoctorId || !reviewVersion) return;
    const trimmed = rejectionReason.trim();
    if (!trimmed) {
      setDecisionError('Rejection reason is required before rejecting verification.');
      return;
    }
    if (trimmed.length > 1000) {
      setDecisionError('Rejection reason must be at most 1000 characters.');
      return;
    }

    runDecision(
      'reject',
      (doctorId) => api.post(`/doctors/${doctorId}/verification/reject`, {
        reason: trimmed,
        reviewVersion,
      }),
      `Doctor #${targetDoctorId} verification has been REJECTED.`,
    );
  };

  const activeDocMap = new Map(detailDocuments.map((doc) => [doc.documentKind, doc]));
  const detailStatus = detailData?.status;
  const detailStatusMeta = VERIFICATION_STATUS_META[detailStatus] ?? {
    label: detailStatus || 'Unknown',
    pillClass: 'status-unverified',
  };

  return (
    <section className="verification-panel" aria-label="Admin Doctor Verification Queue">
      <div className="verification-summary-card">
        <div className="verification-header-row">
          <div>
            <h2>Doctor Professional Verification Queue</h2>
            <p className="verification-subtitle">
              Inspect submitted clinical credentials and preview private evidence strictly during active review.
            </p>
          </div>
          <button
            type="button"
            className="btn-secondary"
            onClick={() => fetchQueue()}
            disabled={queueLoading}
          >
            {queueLoading ? 'Refreshing...' : 'Refresh Queue'}
          </button>
        </div>

        <div className="filter-toolbar">
          <div className="filter-btn-group" role="group" aria-label="Verification status filter">
            {QUEUE_FILTERS.map((filter) => (
              <button
                key={filter.key}
                type="button"
                className={`filter-btn ${statusFilter === filter.key ? 'active' : ''}`}
                onClick={() => handleFilterChange(filter.key)}
              >
                {filter.label}
              </button>
            ))}
          </div>
          <span className="verification-subtitle">
            {queueData.totalElements} doctor(s) matching filter
          </span>
        </div>
      </div>

      {queueError && (
        <div className="dashboard-alert alert-error" role="alert">
          <div>{queueError}</div>
        </div>
      )}

      <div className="admin-verification-layout">
        {/* Left column: Paginated Queue */}
        <div className="verification-card">
          <div className="verification-header-row">
            <h3>Verification Queue</h3>
            <span className="verification-subtitle">
              Page {queueData.totalPages === 0 ? 0 : queueData.page + 1} of {queueData.totalPages}
            </span>
          </div>

          {queueLoading ? (
            <div className="dashboard-alert alert-warning" role="status">
              Loading verification queue...
            </div>
          ) : queueData.items.length === 0 ? (
            <div className="empty-placeholder">
              <h3>No Verification Records</h3>
              <p>No doctors match the selected verification status filter.</p>
            </div>
          ) : (
            <div className="verification-queue-list">
              {queueData.items.map((item) => {
                const pill = VERIFICATION_STATUS_META[item.status] ?? {
                  label: item.status,
                  pillClass: 'status-unverified',
                };
                const isSelected = selectedDoctorId === item.doctorId;

                return (
                  <div
                    key={item.doctorId}
                    className={`verification-queue-item ${isSelected ? 'selected' : ''}`}
                  >
                    <div className="verification-header-row">
                      <div>
                        <strong>{item.doctorName}</strong>{' '}
                        <span className="card-appt-id">Doctor #{item.doctorId}</span>
                      </div>
                      <span className={`status-pill ${pill.pillClass}`}>{item.status}</span>
                    </div>

                    <div className="verification-subtitle">
                      {item.specialization || 'General'} • Reg:{' '}
                      {item.registrationNumber || 'Pending'} ({item.medicalCouncil || 'Pending Council'})
                    </div>

                    <div className="verification-header-row">
                      <span className="verification-subtitle">
                        Submitted: {formatVerificationTimestamp(item.submittedAt)}
                        {item.latestActorRole ? ` • Last update by ${item.latestActorRole}` : ''}
                      </span>
                      <button
                        type="button"
                        className={isSelected ? 'btn-primary' : 'btn-secondary'}
                        onClick={() => handleSelectDoctor(item)}
                      >
                        {isSelected ? 'Viewing Details' : 'Review Dossier'}
                      </button>
                    </div>

                    {item.status === 'REJECTED' && item.latestRejectionReason && (
                      <div className="card-reason">
                        <strong>Rejection Reason:</strong> {item.latestRejectionReason}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          <div className="verification-queue-pagination">
            <button
              type="button"
              className="btn-secondary"
              onClick={() => handlePageChange(page - 1)}
              disabled={queueLoading || !queueData.hasPrevious}
            >
              Previous Page
            </button>
            <span className="verification-subtitle">
              Showing {queueData.items.length} of {queueData.totalElements}
            </span>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => handlePageChange(page + 1)}
              disabled={queueLoading || !queueData.hasNext}
            >
              Next Page
            </button>
          </div>
        </div>

        {/* Right column: Selected Doctor Detail & Review Decision */}
        <div className="verification-card">
          {!selectedDoctorId ? (
            <div className="empty-placeholder">
              <h3>Select a Doctor to Review</h3>
              <p>Choose any doctor from the queue to inspect registration details, history, and verification evidence.</p>
            </div>
          ) : detailLoading && !detailData ? (
            <div className="dashboard-alert alert-warning" role="status">
              Loading details for Doctor #{selectedDoctorId}...
            </div>
          ) : (
            <>
              <div className="verification-header-row">
                <div>
                  <h3>
                    {selectedSummary?.doctorName || `Doctor #${selectedDoctorId}`}{' '}
                    <span className="card-appt-id">Doctor #{selectedDoctorId}</span>
                  </h3>
                  <p className="verification-subtitle">
                    Specialization: {selectedSummary?.specialization || 'Not specified'}
                  </p>
                </div>
                {detailData && (
                  <span className={`status-pill ${detailStatusMeta.pillClass}`}>
                    {detailStatus}
                  </span>
                )}
              </div>

              {detailError && (
                <div className="dashboard-alert alert-error" role="alert">
                  <div>{detailError}</div>
                </div>
              )}

              {decisionNotice && (
                <div className="dashboard-alert alert-success" role="status">
                  <div>{decisionNotice}</div>
                </div>
              )}

              {decisionError && (
                <div className="dashboard-alert alert-error" role="alert">
                  <div>{decisionError}</div>
                </div>
              )}

              {detailData && (
                <>
                  <div className="verification-profile-grid">
                    <div className="verification-field-meta">
                      <span className="verification-field-label">Registration #</span>
                      <span className="verification-field-value">
                        {detailData.registrationNumber || 'Not provided'}
                      </span>
                    </div>
                    <div className="verification-field-meta">
                      <span className="verification-field-label">Medical Council</span>
                      <span className="verification-field-value">
                        {detailData.medicalCouncil || 'Not provided'}
                      </span>
                    </div>
                    <div className="verification-field-meta">
                      <span className="verification-field-label">Registration Year</span>
                      <span className="verification-field-value">
                        {detailData.registrationYear ?? 'Not provided'}
                      </span>
                    </div>
                    <div className="verification-field-meta">
                      <span className="verification-field-label">Review Lock Token</span>
                      <span
                        className="verification-field-value verification-mono"
                        title={detailData.reviewVersion}
                      >
                        {detailData.reviewVersion.slice(0, 14)}...
                      </span>
                    </div>
                  </div>

                  {detailData.latestRejectionReason && (
                    <div
                      className={`dashboard-alert ${detailStatus === 'REJECTED' ? 'alert-error' : 'alert-info'}`}
                      role={detailStatus === 'REJECTED' ? 'alert' : 'status'}
                    >
                      <div>
                        <strong>
                          {detailStatus === 'REJECTED' ? 'Current Rejection Reason:' : 'Previous Rejection Reason:'}
                        </strong>{' '}
                        {detailData.latestRejectionReason}
                      </div>
                    </div>
                  )}

                  {/* Workflow Transitions */}
                  {(detailStatus === 'SUBMITTED' || detailStatus === 'VERIFIED') && (
                    <div className="verification-doc-box">
                      <div className="verification-header-row">
                        <div>
                          <h4>{detailStatus === 'VERIFIED' ? 'Reopen Verification Review' : 'Start Administrative Review'}</h4>
                          <p className="verification-subtitle">
                            {detailStatus === 'VERIFIED'
                              ? 'Moving a verified doctor back to UNDER_REVIEW retires active bookable offers until re-verified.'
                              : 'Move this submission to UNDER_REVIEW before inspecting private documents or making a decision.'}
                          </p>
                        </div>
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={handleStartReview}
                          disabled={Boolean(decisionBusy)}
                        >
                          {decisionBusy === 'review' ? 'Starting Review...' : 'Start Review'}
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Active Documents & Private Preview */}
                  <div className="verification-doc-box">
                    <div className="verification-header-row">
                      <h4>Submitted Evidence Metadata</h4>
                      <span className="verification-subtitle">
                        {detailStatus === 'UNDER_REVIEW'
                          ? 'Preview enabled (no-store decrypted stream)'
                          : 'Preview locked — available only in UNDER_REVIEW'}
                      </span>
                    </div>

                    <div className="verification-documents-grid">
                      {['ID_PROOF', 'QUALIFICATION'].map((kind) => {
                        const kindMeta = DOCUMENT_KIND_CONFIG[kind];
                        const doc = activeDocMap.get(kind);
                        const canPreview = detailStatus === 'UNDER_REVIEW' && Boolean(doc) && !previewLoading;

                        return (
                          <div key={kind} className="verification-queue-item">
                            <div className="verification-header-row">
                              <strong>{kindMeta.label}</strong>
                              <span className={`status-pill ${doc ? 'status-verified' : 'status-unverified'}`}>
                                {doc ? doc.mediaType : 'Missing'}
                              </span>
                            </div>
                            {doc && (
                              <div className="verification-subtitle">
                                {formatFileSize(doc.fileSize)} • Uploaded {formatVerificationTimestamp(doc.createdAt)}
                              </div>
                            )}
                            <button
                              type="button"
                              className="btn-secondary"
                              onClick={() => handlePreviewDocument(kind)}
                              disabled={!canPreview}
                            >
                              {previewLoading && previewKind === kind
                                ? 'Loading Private Image...'
                                : `Preview ${kind === 'ID_PROOF' ? 'ID Proof' : 'Qualification'}`}
                            </button>
                          </div>
                        );
                      })}
                    </div>

                    {previewError && (
                      <div className="dashboard-alert alert-error" role="alert">
                        <div>{previewError}</div>
                      </div>
                    )}

                    {previewUrl && previewKind && (
                      <div className="verification-preview-frame" data-testid="admin-document-preview">
                        <div className="verification-header-row">
                          <strong>
                            Private Evidence Preview: {DOCUMENT_KIND_CONFIG[previewKind].label}
                          </strong>
                          <button
                            type="button"
                            className="btn-secondary"
                            onClick={revokePreview}
                          >
                            Close Preview
                          </button>
                        </div>
                        <img
                          src={previewUrl}
                          alt={`Doctor #${selectedDoctorId} ${DOCUMENT_KIND_CONFIG[previewKind].label}`}
                          className="verification-preview-image"
                        />
                      </div>
                    )}
                  </div>

                  {/* Approve / Reject Controls during UNDER_REVIEW */}
                  {detailStatus === 'UNDER_REVIEW' && (
                    <div className="verification-doc-box">
                      <h4>Review Decision</h4>
                      <div className="verification-form-field">
                        <label htmlFor="admin-rejection-reason">
                          Rejection Reason (mandatory if rejecting)
                        </label>
                        <textarea
                          id="admin-rejection-reason"
                          maxLength={1000}
                          className="verification-textarea"
                          value={rejectionReason}
                          onChange={(e) => setRejectionReason(e.target.value)}
                          placeholder="Provide a clear, actionable reason for the doctor if rejecting (e.g. blurry certificate, mismatched registration number)..."
                          disabled={Boolean(decisionBusy)}
                        />
                      </div>

                      <div className="verification-action-bar">
                        <button
                          type="button"
                          className="btn-confirm-complete"
                          onClick={handleApprove}
                          disabled={Boolean(decisionBusy)}
                        >
                          {decisionBusy === 'approve' ? 'Approving...' : 'Approve Verification'}
                        </button>
                        <button
                          type="button"
                          className="btn-danger"
                          onClick={handleReject}
                          disabled={Boolean(decisionBusy)}
                        >
                          {decisionBusy === 'reject' ? 'Rejecting...' : 'Reject Verification'}
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Audit Transition History */}
                  <div>
                    <div className="verification-header-row" style={{ marginBottom: '8px' }}>
                      <h4>Transition History</h4>
                      <span className="verification-subtitle">{detailData.history.length} event(s)</span>
                    </div>
                    {detailData.history.length === 0 ? (
                      <p className="verification-subtitle">No transitions recorded yet.</p>
                    ) : (
                      <div className="verification-history-list">
                        {detailData.history.map((entry, idx) => (
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
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}

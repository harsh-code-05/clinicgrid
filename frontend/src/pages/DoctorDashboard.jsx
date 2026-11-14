import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { mutationErrorMessage, requireAppointmentResult, requireAppointmentsList } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import AvailabilityManager from '../components/AvailabilityManager';
import DoctorVerificationPanel from '../components/DoctorVerificationPanel';
import AppointmentListState from '../components/AppointmentListState';
import AppointmentMetricsState from '../components/AppointmentMetricsState';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import { MAX_CANCELLATION_REASON_LENGTH, buildCancelRequestPayload, validateCancellationReason, formatCancellationNotice, formatCancelledByRole, formatSlotOutcomeLabel, formatCancelledAt, shouldRefreshAfterCancelError } from '../utils/appointmentCancellation';
import './DoctorDashboard.css';

function formatDate(dateStr) {
  if (!dateStr) return 'N/A';
  try {
    const [year, month, day] = dateStr.split('-');
    if (!year || !month || !day) return dateStr;
    const date = new Date(Number(year), Number(month) - 1, Number(day));
    return date.toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  } catch {
    return dateStr;
  }
}

function formatTime(timeStr) {
  if (!timeStr) return '';
  const parts = timeStr.split(':');
  if (parts.length < 2) return timeStr;
  const hours = parseInt(parts[0], 10);
  const minutes = parts[1];
  const ampm = hours >= 12 ? 'PM' : 'AM';
  const displayHours = hours % 12 || 12;
  return `${displayHours}:${minutes} ${ampm}`;
}

export default function DoctorDashboard() {
  const { user, logout, formDrafts } = useAuth();
  const navigate = useNavigate();

  const userEmail = user?.email;
  const userId = user?.id;
  const [doctorProfile, setDoctorProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Active section tab: 'appointments' or 'availability'
  const [activeTab, setActiveTab] = useState(() =>
    formDrafts.get(user.id, 'availability') ? 'availability' : 'appointments',
  );

  // Appointments state
  const [appointments, setAppointments] = useState([]);
  const [appointmentsLoading, setAppointmentsLoading] = useState(true);
  const [appointmentsError, setAppointmentsError] = useState('');
  const [appointmentsLoaded, setAppointmentsLoaded] = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Global notice/banner
  const [globalNotice, setGlobalNotice] = useState('');
  const [refreshWarning, setRefreshWarning] = useState('');

  // Complete appointment modal state
  const [completingAppt, setCompletingAppt] = useState(null);
  const [isCompleting, setIsCompleting] = useState(false);
  const [completeError, setCompleteError] = useState('');

  // Cancel appointment modal state
  const [cancellingAppt, setCancellingAppt] = useState(null);
  const [cancelReason, setCancelReason] = useState('');
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');
  const [cancelRefreshWarning, setCancelRefreshWarning] = useState('');

  const cancelInProgressRef = useRef(false);
  const mountedRef = useRef(true);
  const activeUserIdRef = useRef(userId);
  const sequencerRef = useRef(null);
  const getSequencer = useCallback(() => {
    if (sequencerRef.current == null) {
      sequencerRef.current = new RequestSequencer();
    }
    return sequencerRef.current;
  }, []);

  useEffect(() => {
    activeUserIdRef.current = userId;
  }, [userId]);

  useEffect(() => {
    mountedRef.current = true;
    const sequencer = getSequencer();
    sequencer.mount();
    return () => {
      mountedRef.current = false;
      sequencer.dispose();
    };
  }, [getSequencer]);

  // 1. Fetch Doctor Profile
  const fetchDoctorProfile = useCallback(async () => {
    if (!userId) return;
    setLoading(true);
    setError('');

    try {
      const res = await api.get('/doctors');
      const doctors = Array.isArray(res.data) ? res.data : [];
      const match = doctors.find(
        (doc) => doc.user?.id === userId,
      );

      if (match) {
        setDoctorProfile(match);
      } else {
        setDoctorProfile(null);
        setError(
          `No active doctor profile is linked to account "${userEmail}". If you recently registered, your doctor application may still be pending administrative approval.`,
        );
      }
    } catch (err) {
      setError(
        err.response?.data?.message || 'Failed to load doctor profile. Please refresh.',
      );
    } finally {
      setLoading(false);
    }
  }, [userEmail, userId]);

  // 2. Fetch Appointments for logged-in Doctor
  const fetchAppointments = useCallback(async () => {
    return executeSequencedRequest(
      getSequencer(),
      async (signal) => {
        const res = await api.get('/appointments', { signal });
        return requireAppointmentsList(res);
      },
      {
        onStart: () => {
          setAppointmentsLoading(true);
          setAppointmentsError('');
        },
        onSuccess: (data) => {
          setAppointments(data);
          setAppointmentsLoaded(true);
          setAppointmentsLoading(false);
          setRefreshWarning('');
          setCancelRefreshWarning('');
        },
        onError: (err) => {
          setAppointmentsError(
            mutationErrorMessage(err, 'Failed to load appointments. Please refresh.'),
          );
          setAppointmentsLoading(false);
        },
      },
    );
  }, [getSequencer]);

  // Initial Doctor Profile Load
  useEffect(() => {
    let ignore = false;
    async function load() {
      if (!ignore) {
        await fetchDoctorProfile();
      }
    }
    load();
    return () => {
      ignore = true;
    };
  }, [fetchDoctorProfile]);

  // Fetch appointments when doctor profile is loaded
  useEffect(() => {
    let ignore = false;
    if (doctorProfile) {
      async function loadAppts() {
        if (!ignore) {
          await fetchAppointments();
        }
      }
      loadAppts();
    }
    return () => {
      ignore = true;
    };
  }, [doctorProfile, fetchAppointments]);

  // Logout Handler
  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout error:', err);
    }
  };

  // Complete Appointment Execution
  const handleConfirmComplete = async () => {
    if (!completingAppt) return;
    setIsCompleting(true);
    setCompleteError('');
    try {
      const response = await api.patch(`/appointments/${completingAppt.id}/complete`);
      requireAppointmentResult(response, 'COMPLETED', (data) => data.id === completingAppt.id);
      const notice = `Appointment #${completingAppt.id} was marked as completed.`;
      setCompletingAppt(null);
      const refreshed = await fetchAppointments();
      setGlobalNotice(refreshed
        ? notice
        : `${notice} The list could not be refreshed; displayed data may be stale.`);
    } catch (err) {
      setCompleteError(
        mutationErrorMessage(err, 'Failed to complete appointment. Please check appointment status and time.'),
      );
    } finally {
      setIsCompleting(false);
    }
  };

  // Cancel Appointment Execution
  const openCancelModal = (appt) => {
    setCancellingAppt(appt);
    setCancelReason('');
    setCancelError('');
    setCancelRefreshWarning('');
  };

  const closeCancelModal = () => {
    if (isCancelling) return;
    setCancellingAppt(null);
    setCancelReason('');
    setCancelError('');
    setCancelRefreshWarning('');
  };

  const handleConfirmCancel = async () => {
    if (!cancellingAppt || cancelInProgressRef.current) return;
    const targetAppt = cancellingAppt;
    const reasonError = validateCancellationReason(cancelReason);
    if (reasonError) {
      setCancelError(reasonError);
      return;
    }
    cancelInProgressRef.current = true;
    const requestUserId = activeUserIdRef.current;
    setIsCancelling(true);
    setCancelError('');
    setCancelRefreshWarning('');
    setRefreshWarning('');
    try {
      const response = await api.patch(
        `/appointments/${targetAppt.id}/cancel`,
        buildCancelRequestPayload(cancelReason),
      );
      const cancelled = requireAppointmentResult(
        response,
        'CANCELLED',
        (data) => data.id === targetAppt.id,
      );
      if (!mountedRef.current || activeUserIdRef.current !== requestUserId) {
        return;
      }
      const notice = formatCancellationNotice(cancelled);
      setCancellingAppt(null);
      setCancelReason('');
      setCancelRefreshWarning('');
      const refreshed = await fetchAppointments();
      if (!mountedRef.current || activeUserIdRef.current !== requestUserId) {
        return;
      }
      setGlobalNotice(notice);
      setRefreshWarning(
        refreshed
          ? ''
          : 'Appointments could not be refreshed automatically; displayed data may be stale.',
      );
    } catch (err) {
      if (!mountedRef.current || activeUserIdRef.current !== requestUserId) {
        return;
      }
      setCancelError(
        mutationErrorMessage(err, 'Failed to cancel appointment. Please check appointment status.'),
      );
      if (shouldRefreshAfterCancelError(err)) {
        const refreshed = await fetchAppointments();
        if (!mountedRef.current || activeUserIdRef.current !== requestUserId) {
          return;
        }
        if (!refreshed) {
          const warning = 'Unable to refresh the appointment list automatically; displayed data may be stale.';
          setCancelRefreshWarning(warning);
          setRefreshWarning(warning);
        } else {
          setCancelRefreshWarning('');
          setRefreshWarning('');
        }
      }
    } finally {
      cancelInProgressRef.current = false;
      if (mountedRef.current) {
        setIsCancelling(false);
      }
    }
  };

  // Summary Metrics
  const totalCount = appointments.length;
  const confirmedCount = appointments.filter((a) => a.status === 'CONFIRMED').length;
  const completedCount = appointments.filter((a) => a.status === 'COMPLETED').length;
  const cancelledCount = appointments.filter((a) => a.status === 'CANCELLED').length;
  const noShowCount = appointments.filter((a) => a.status === 'NO_SHOW').length;

  // Filtered Appointments
  const filteredAppointments = appointments.filter((appt) => {
    if (statusFilter === 'ALL') return true;
    return appt.status === statusFilter;
  });

  const doctorDisplayName = doctorProfile
    ? `Dr. ${doctorProfile.firstName} ${doctorProfile.lastName} (${userEmail})`
    : userEmail;

  return (
    <div className="doctor-dashboard">
      <header className="dashboard-header">
        <div className="dashboard-brand">
          <h1>Hospital Management System</h1>
          <span className="brand-badge">Doctor Portal</span>
        </div>
        <div className="dashboard-user-area">
          <span className="user-email-tag">{doctorDisplayName}</span>
          <button
            type="button"
            className="btn-secondary"
            onClick={handleLogout}
          >
            Sign Out
          </button>
        </div>
      </header>

      {/* Tabs Navigation */}
      {doctorProfile && !loading && (
        <nav className="dashboard-tabs" aria-label="Doctor portal tabs">
          <button
            type="button"
            className={`tab-btn ${activeTab === 'appointments' ? 'active' : ''}`}
            onClick={() => setActiveTab('appointments')}
          >
            My Appointments {appointmentsLoaded ? `(${totalCount})` : ''}
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'availability' ? 'active' : ''}`}
            onClick={() => setActiveTab('availability')}
          >
            Availability & Slots
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'verification' ? 'active' : ''}`}
            onClick={() => setActiveTab('verification')}
          >
            Verification
          </button>
        </nav>
      )}

      <main className="dashboard-content">
        {/* Global Notice Alert */}
        {globalNotice && (
          <div className="dashboard-alert alert-success" role="status">
            <div style={{ flex: 1 }}>{globalNotice}</div>
            <button
              type="button"
              onClick={() => setGlobalNotice('')}
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                fontWeight: 'bold',
                color: 'inherit',
              }}
              aria-label="Dismiss notice"
            >
              ×
            </button>
          </div>
        )}

        {refreshWarning && (
          <div className="dashboard-alert alert-warning" role="status">
            <div style={{ flex: 1 }}>{refreshWarning}</div>
            <button
              type="button"
              onClick={() => setRefreshWarning('')}
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                fontWeight: 'bold',
                color: 'inherit',
              }}
              aria-label="Dismiss warning"
            >
              ×
            </button>
          </div>
        )}

        {loading ? (
          <div className="dashboard-alert alert-warning" role="status">
            Loading doctor profile...
          </div>
        ) : error ? (
          <div className="dashboard-alert alert-error" role="alert">
            <div>
              <strong>Doctor Profile Status:</strong> {error}
            </div>
          </div>
        ) : doctorProfile ? (
          <>
            {/* Tab 1: My Appointments */}
            {activeTab === 'appointments' && (
              <>
                {/* Summary Metrics */}
                <AppointmentMetricsState
                  loading={appointmentsLoading}
                  error={appointmentsError}
                  hasLoaded={appointmentsLoaded}
                  metrics={{
                    total: totalCount,
                    confirmed: confirmedCount,
                    completed: completedCount,
                    cancelled: cancelledCount,
                    noShow: noShowCount,
                  }}
                />

                {/* Status Filter Toolbar */}
                <div className="filter-toolbar">
                  <div className="filter-btn-group" role="group" aria-label="Status filter">
                    {['ALL', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'].map((st) => (
                      <button
                        key={st}
                        type="button"
                        className={`filter-btn ${statusFilter === st ? 'active' : ''}`}
                        onClick={() => setStatusFilter(st)}
                      >
                        {st === 'ALL' ? 'All Appointments' : st}
                      </button>
                    ))}
                  </div>

                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={fetchAppointments}
                    disabled={appointmentsLoading}
                  >
                    {appointmentsLoading ? 'Refreshing...' : 'Refresh List'}
                  </button>
                </div>

                {/* Appointments List / Stack */}
                <AppointmentListState
                  loading={appointmentsLoading}
                  error={appointmentsError}
                  hasLoaded={appointmentsLoaded}
                  isEmpty={filteredAppointments.length === 0}
                  loadingMessage="Loading your appointments..."
                  emptyMessage={statusFilter === 'ALL'
                    ? 'You do not have any patient appointments booked yet. Patient bookings will appear here once slots are reserved.'
                    : `You have no ${statusFilter.toLowerCase()} appointments.`}
                >
                  <div className="appointments-stack">
                    {filteredAppointments.map((appt) => {
                      const patientName = appt.patient
                        ? `${appt.patient.firstName} ${appt.patient.lastName}`
                        : 'Patient Details Unavailable';
                      const patientDob = formatDate(appt.patient?.dateOfBirth);
                      const patientPhone = appt.patient?.phone || 'Not provided';
                      const slotDate = formatDate(appt.slot?.date);
                      const slotTime = `${formatTime(appt.slot?.startTime)} – ${formatTime(
                        appt.slot?.endTime,
                      )}`;
                      const statusClass =
                        appt.status === 'CONFIRMED'
                          ? 'status-confirmed'
                          : appt.status === 'COMPLETED'
                          ? 'status-completed'
                          : appt.status === 'CANCELLED'
                          ? 'status-cancelled'
                          : 'status-noshow';

                      return (
                        <div key={appt.id} className="appointment-card">
                          <div className="card-main">
                            <div className="card-header-line">
                              <span className="card-date-time">
                                {slotDate} • {slotTime}
                              </span>
                              <span className={`status-pill ${statusClass}`}>
                                {appt.status}
                              </span>
                            </div>

                            {/* Patient Information Grid */}
                            <div className="patient-info-grid">
                              <div className="patient-info-item">
                                <span className="patient-info-label">Patient Name</span>
                                <span className="patient-info-val">{patientName}</span>
                              </div>
                              <div className="patient-info-item">
                                <span className="patient-info-label">Date of Birth</span>
                                <span className="patient-info-val">{patientDob}</span>
                              </div>
                              <div className="patient-info-item">
                                <span className="patient-info-label">Phone</span>
                                <span className="patient-info-val">{patientPhone}</span>
                              </div>
                            </div>

                            {/* Appointment Reason */}
                            <div className="card-reason">
                              <strong>Reason for Visit:</strong>{' '}
                              {appt.reason || 'General Consultation'}
                            </div>

                            {appt.status === 'CANCELLED' && (
                              <div className="cancellation-details">
                                <div className="cancellation-details-title">Cancellation Details</div>
                                {appt.cancelledAt || appt.cancelledByRole || appt.cancellationReason || appt.cancellationSlotOutcome ? (
                                  <div className="cancellation-details-list">
                                    {(appt.cancelledByRole || appt.cancelledAt) && (
                                      <div>
                                        <strong>Cancelled by:</strong>{' '}
                                        {formatCancelledByRole(appt.cancelledByRole) || 'Recorded'}
                                        {appt.cancelledAt ? ` on ${formatCancelledAt(appt.cancelledAt)}` : ''}
                                      </div>
                                    )}
                                    {appt.cancellationSlotOutcome && (
                                      <div>
                                        <strong>Slot status:</strong>{' '}
                                        {formatSlotOutcomeLabel(appt.cancellationSlotOutcome)}
                                      </div>
                                    )}
                                    {appt.cancellationReason && (
                                      <div>
                                        <strong>Cancellation reason:</strong> {appt.cancellationReason}
                                      </div>
                                    )}
                                  </div>
                                ) : (
                                  <div className="cancellation-details-legacy">
                                    Cancelled before detailed tracking was enabled.
                                  </div>
                                )}
                              </div>
                            )}

                            {/* Footer Meta */}
                            <div className="card-footer-meta">
                              Appointment #{appt.id} • Booked on{' '}
                              {formatDate(appt.createdAt ? appt.createdAt.split('T')[0] : '')}
                            </div>
                          </div>

                          {/* Action Buttons for Confirmed Appointments */}
                          {appt.status === 'CONFIRMED' && (
                            <div className="card-actions">
                              <button
                                type="button"
                                className="btn-complete-appt"
                                onClick={() => {
                                  setCompletingAppt(appt);
                                  setCompleteError('');
                                }}
                              >
                                Complete
                              </button>
                              <button
                                type="button"
                                className="btn-cancel-appt"
                                onClick={() => openCancelModal(appt)}
                              >
                                Cancel
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </AppointmentListState>
              </>
            )}

            {/* Tab 2: Availability & Slots */}
            {activeTab === 'availability' && (
              <AvailabilityManager
                doctorId={doctorProfile.id}
                doctor={doctorProfile}
                isAdmin={false}
                onSlotsGenerated={fetchAppointments}
              />
            )}

            {/* Tab 3: Professional Verification */}
            {activeTab === 'verification' && (
              <DoctorVerificationPanel doctorProfile={doctorProfile} />
            )}
          </>
        ) : null}
      </main>

      {/* Complete Appointment Confirmation Modal */}
      {completingAppt && (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="complete-modal-title"
        >
          <div className="modal-dialog">
            <h3 id="complete-modal-title">Complete Appointment</h3>
            <p>
              Are you sure you want to mark Appointment #{completingAppt.id} with{' '}
              <strong>
                {completingAppt.patient
                  ? `${completingAppt.patient.firstName} ${completingAppt.patient.lastName}`
                  : 'Patient'}
              </strong>{' '}
              as completed?
            </p>
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '16px' }}>
              <strong>Scheduled Time:</strong> {formatDate(completingAppt.slot?.date)} (
              {formatTime(completingAppt.slot?.startTime)} –{' '}
              {formatTime(completingAppt.slot?.endTime)})
            </div>

            {completeError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '16px' }}
                role="alert"
              >
                {completeError}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setCompletingAppt(null);
                  setCompleteError('');
                }}
                disabled={isCompleting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn-confirm-complete"
                onClick={handleConfirmComplete}
                disabled={isCompleting}
              >
                {isCompleting ? 'Completing...' : 'Mark as Completed'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Cancel Appointment Confirmation Modal */}
      {cancellingAppt && (
        <div
          className="modal-backdrop"
          onClick={closeCancelModal}
          role="dialog"
          aria-modal="true"
          aria-labelledby="cancel-modal-title"
        >
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3 id="cancel-modal-title">Cancel Appointment</h3>
            <p>
              Are you sure you want to cancel Appointment #{cancellingAppt.id} with{' '}
              <strong>
                {cancellingAppt.patient
                  ? `${cancellingAppt.patient.firstName} ${cancellingAppt.patient.lastName}`
                  : 'Patient'}
              </strong>
              ? Eligible future slots are handled automatically according to your current schedule rules.
            </p>
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '12px' }}>
              <strong>Scheduled Time:</strong> {formatDate(cancellingAppt.slot?.date)} (
              {formatTime(cancellingAppt.slot?.startTime)} –{' '}
              {formatTime(cancellingAppt.slot?.endTime)})
            </div>

            <div className="cancel-reason-field">
              <label htmlFor="doctor-cancel-reason">
                Cancellation Reason (Optional)
              </label>
              <textarea
                id="doctor-cancel-reason"
                className="modal-textarea"
                rows="3"
                maxLength={MAX_CANCELLATION_REASON_LENGTH}
                placeholder="Share a brief reason for cancelling (up to 500 characters)"
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                disabled={isCancelling}
              />
              <div className="char-counter">
                {cancelReason.length}/{MAX_CANCELLATION_REASON_LENGTH}
              </div>
            </div>

            {cancelError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '16px' }}
                role="alert"
              >
                {cancelError}
              </div>
            )}

            {cancelRefreshWarning && (
              <div
                className="dashboard-alert alert-warning"
                style={{ marginBottom: '16px' }}
                role="status"
              >
                {cancelRefreshWarning}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="btn-secondary"
                onClick={closeCancelModal}
                disabled={isCancelling}
              >
                Keep Appointment
              </button>
              <button
                type="button"
                className="btn-danger"
                onClick={handleConfirmCancel}
                disabled={isCancelling}
              >
                {isCancelling ? 'Cancelling...' : 'Confirm Cancellation'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

import { useState, useEffect, useCallback, useRef } from 'react';
import billService, { formatCurrency } from '../api/billService';
import InvoiceModal from './InvoiceModal';
import './AdminBillingPanel.css';

export default function AdminBillingPanel() {
  const [bills, setBills] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [successMsg, setSuccessMsg] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedBill, setSelectedBill] = useState(null);

  // Request race guard & cancellation
  const activeRequestIdRef = useRef(0);
  const currentFetchControllerRef = useRef(null);
  const latestFilterRef = useRef(statusFilter);

  useEffect(() => {
    latestFilterRef.current = statusFilter;
  }, [statusFilter]);

  // Generate Bill State
  const [genApptId, setGenApptId] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [genError, setGenError] = useState('');

  // Payment Modal State
  const [payingBill, setPayingBill] = useState(null);
  const [payMethod, setPayMethod] = useState('MANUAL_CASH');
  const [payRef, setPayRef] = useState('');
  const [payNotes, setPayNotes] = useState('');
  const [isProcessingPay, setIsProcessingPay] = useState(false);

  // Void Modal State
  const [voidingBill, setVoidingBill] = useState(null);
  const [voidReason, setVoidReason] = useState('');
  const [isProcessingVoid, setIsProcessingVoid] = useState(false);

  const fetchBills = useCallback(async () => {
    if (currentFetchControllerRef.current) {
      currentFetchControllerRef.current.abort();
    }
    const controller = new AbortController();
    currentFetchControllerRef.current = controller;
    const thisRequestId = ++activeRequestIdRef.current;
    // ALWAYS read from latestFilterRef to prevent stale closure after delayed mutations
    const currentFilter = latestFilterRef.current;

    try {
      setLoading(true);
      setError('');
      const filter = currentFilter === 'ALL' ? null : currentFilter;
      const data = await billService.getAllBills(filter, controller.signal);
      if (thisRequestId !== activeRequestIdRef.current) return;
      setBills(data);
    } catch (err) {
      if (err.name === 'AbortError' || err.name === 'CanceledError' || controller.signal.aborted) return;
      if (thisRequestId === activeRequestIdRef.current) {
        setError(err?.response?.data?.message || err.message || 'Failed to fetch billing records');
      }
    } finally {
      if (thisRequestId === activeRequestIdRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (active) {
        fetchBills();
      }
    });
    return () => {
      active = false;
      if (currentFetchControllerRef.current) {
        currentFetchControllerRef.current.abort();
      }
    };
  }, [statusFilter, fetchBills]);

  const handleGenerateBill = async (e) => {
    e.preventDefault();
    const rawInput = String(genApptId || '').trim();
    if (!/^[1-9]\d*$/.test(rawInput)) {
      setGenError('Please enter a valid appointment ID');
      return;
    }
    const apptId = Number(rawInput);
    if (!Number.isSafeInteger(apptId) || apptId <= 0) {
      setGenError('Please enter a valid appointment ID');
      return;
    }
    try {
      setIsGenerating(true);
      setGenError('');
      setSuccessMsg('');
      const created = await billService.generateBill(apptId);
      setSuccessMsg(`Invoice ${created.invoiceNumber} generated successfully for Appointment #${apptId}`);
      setGenApptId('');
      fetchBills();
    } catch (err) {
      setGenError(err?.response?.data?.message || err.message || 'Failed to generate invoice');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleConfirmPayment = async (e) => {
    e.preventDefault();
    if (!payingBill) return;
    const targetBill = payingBill;
    try {
      setIsProcessingPay(true);
      setError('');
      await billService.recordPayment(targetBill.id, {
        paymentMethod: payMethod,
        paymentReference: payRef.trim(),
        notes: payNotes.trim(),
      });
      setSuccessMsg(`Invoice ${targetBill.invoiceNumber} marked as PAID`);
      setPayingBill((cur) => {
        if (cur?.id === targetBill.id) {
          setPayRef('');
          setPayNotes('');
          return null;
        }
        return cur;
      });
      fetchBills();
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Payment processing failed');
    } finally {
      setIsProcessingPay(false);
    }
  };

  const handleConfirmVoid = async (e) => {
    e.preventDefault();
    if (!voidingBill) return;
    const targetBill = voidingBill;
    if (!voidReason.trim()) {
      setError('Void reason is required');
      return;
    }
    try {
      setIsProcessingVoid(true);
      setError('');
      await billService.voidBill(targetBill.id, { reason: voidReason.trim() });
      setSuccessMsg(`Invoice ${targetBill.invoiceNumber} successfully voided`);
      setVoidingBill((cur) => {
        if (cur?.id === targetBill.id) {
          setVoidReason('');
          return null;
        }
        return cur;
      });
      fetchBills();
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Voiding invoice failed');
    } finally {
      setIsProcessingVoid(false);
    }
  };

  const filteredBills = bills.filter((b) => {
    if (statusFilter !== 'ALL' && b.status !== statusFilter) return false;
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      b.invoiceNumber?.toLowerCase().includes(q) ||
      b.patientName?.toLowerCase().includes(q) ||
      b.doctorName?.toLowerCase().includes(q) ||
      String(b.appointmentId).includes(q)
    );
  });

  return (
    <div className="admin-billing-panel" data-testid="admin-billing-panel">
      <div className="panel-header">
        <div>
          <h2>Hospital Billing & Invoice Management</h2>
          <p className="panel-subtitle">Generate consultation invoices, track manual demo payments, and manage billing audit records</p>
        </div>
        <button
          type="button"
          className="btn-refresh"
          onClick={() => fetchBills()}
          disabled={loading}
        >
          {loading ? 'Refreshing...' : 'Refresh Records'}
        </button>
      </div>

      {/* Notifications */}
      {successMsg && (
        <div className="alert-banner alert-success" role="status">
          <span>{successMsg}</span>
          <button type="button" onClick={() => setSuccessMsg('')}>&times;</button>
        </div>
      )}
      {error && (
        <div className="alert-banner alert-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError('')}>&times;</button>
        </div>
      )}

      {/* Action Bar: Generate Invoice & Filters */}
      <div className="admin-billing-grid">
        {/* Generate Invoice Box */}
        <div className="card-generate-bill">
          <h3>Generate Consultation Invoice</h3>
          <p className="card-desc">Issue an invoice for an eligible confirmed or completed appointment</p>
          <form onSubmit={handleGenerateBill} className="generate-form">
            <div className="input-group">
              <input
                type="number"
                min="1"
                placeholder="Appointment ID"
                value={genApptId}
                onChange={(e) => setGenApptId(e.target.value)}
                disabled={isGenerating}
                required
                aria-label="Appointment ID"
              />
              <button type="submit" className="btn-generate" disabled={isGenerating}>
                {isGenerating ? 'Generating...' : 'Generate Bill'}
              </button>
            </div>
            {genError && <p className="form-error-msg" role="alert">{genError}</p>}
          </form>
        </div>

        {/* Search & Filter Controls */}
        <div className="card-filters">
          <h3>Search & Filter Bills</h3>
          <div className="filter-controls">
            <input
              type="text"
              placeholder="Search by invoice #, patient, doctor..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="search-input"
            />
            <div className="status-tabs" role="tablist">
              {['ALL', 'UNPAID', 'PAID', 'VOID'].map((st) => (
                <button
                  key={st}
                  type="button"
                  className={`status-tab-btn ${statusFilter === st ? 'active' : ''}`}
                  onClick={() => setStatusFilter(st)}
                >
                  {st}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Bills List Table */}
      <div className="admin-table-container">
        {loading && !bills.length ? (
          <div className="panel-loading">
            <div className="spinner"></div>
            <p>Loading billing records...</p>
          </div>
        ) : filteredBills.length === 0 ? (
          <div className="panel-empty">
            <p>No billing records found matching the current criteria.</p>
          </div>
        ) : (
          <table className="admin-bills-table">
            <thead>
              <tr>
                <th>Invoice #</th>
                <th>Patient</th>
                <th>Doctor</th>
                <th>Appt #</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Issued At</th>
                <th>Audit Info</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredBills.map((b) => (
                <tr key={b.id} data-testid={`admin-bill-row-${b.id}`}>
                  <td className="cell-inv">
                    <strong>{b.invoiceNumber}</strong>
                  </td>
                  <td>
                    <div>{b.patientName}</div>
                    <div className="cell-sub">{b.patientEmail || `#${b.patientId}`}</div>
                  </td>
                  <td>
                    <div>{b.doctorName}</div>
                    <div className="cell-sub">{b.doctorSpecialization}</div>
                  </td>
                  <td>
                    <span>#{b.appointmentId}</span>
                    {b.appointmentStatus && (
                      <span className="cell-sub"> ({b.appointmentStatus})</span>
                    )}
                  </td>
                  <td className="cell-price">
                    <strong>{formatCurrency(b.amount)}</strong>
                  </td>
                  <td>
                    <span className={`status-pill status-${b.status?.toLowerCase()}`}>
                      {b.status}
                    </span>
                  </td>
                  <td className="cell-date">
                    {b.issuedAt ? new Date(b.issuedAt).toLocaleDateString() : 'N/A'}
                  </td>
                  <td className="cell-audit">
                    {b.status === 'PAID' && (
                      <div className="audit-info">
                        Paid by {b.paidByAdminEmail || `Admin #${b.paidByAdminId}`}
                        <div className="cell-sub">{b.paidAt ? new Date(b.paidAt).toLocaleTimeString() : ''}</div>
                      </div>
                    )}
                    {b.status === 'VOID' && (
                      <div className="audit-info">
                        Voided: {b.voidReason || 'No reason'}
                      </div>
                    )}
                    {b.status === 'UNPAID' && <span className="cell-sub">Awaiting Desk Payment</span>}
                  </td>
                  <td className="cell-actions">
                    <button
                      type="button"
                      className="btn-action btn-view"
                      onClick={() => setSelectedBill(b)}
                      title="View printable invoice"
                    >
                      View
                    </button>
                    {b.status === 'UNPAID' && (
                      <>
                        <button
                          type="button"
                          className="btn-action btn-pay"
                          disabled={isProcessingPay || isProcessingVoid}
                          onClick={() => {
                            if (isProcessingPay || isProcessingVoid) return;
                            setPayingBill(b);
                            setPayRef(`TX-${b.invoiceNumber.split('-').pop()}`);
                            setPayNotes('');
                          }}
                          data-testid={`pay-bill-${b.id}`}
                        >
                          Mark Paid
                        </button>
                        <button
                          type="button"
                          className="btn-action btn-void"
                          disabled={isProcessingPay || isProcessingVoid}
                          onClick={() => {
                            if (isProcessingPay || isProcessingVoid) return;
                            setVoidingBill(b);
                            setVoidReason('');
                          }}
                          data-testid={`void-bill-${b.id}`}
                        >
                          Void
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Modal: Mark Paid */}
      {payingBill && (
        <div className="modal-backdrop" onClick={() => !isProcessingPay && setPayingBill(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>Record Manual Demo Payment</h3>
            <p className="modal-sub">
              Invoice <strong>{payingBill.invoiceNumber}</strong> &bull; Amount:{' '}
              <strong>{formatCurrency(payingBill.amount)}</strong>
            </p>
            <form onSubmit={handleConfirmPayment}>
              <div className="form-field">
                <label>Payment Method</label>
                <select value={payMethod} onChange={(e) => setPayMethod(e.target.value)}>
                  <option value="MANUAL_CASH">Cash (Hospital Billing Desk)</option>
                  <option value="MANUAL_CARD">Point of Sale Terminal (Card)</option>
                  <option value="MANUAL_DEMO">Manual Demo Payment</option>
                </select>
              </div>
              <div className="form-field">
                <label>Payment Reference / Receipt #</label>
                <input
                  type="text"
                  value={payRef}
                  onChange={(e) => setPayRef(e.target.value)}
                  placeholder="Receipt or Transaction memo"
                />
              </div>
              <div className="form-field">
                <label>Notes (Optional)</label>
                <textarea
                  rows="2"
                  value={payNotes}
                  onChange={(e) => setPayNotes(e.target.value)}
                  placeholder="Additional payment notes"
                />
              </div>
              <div className="modal-btn-row">
                <button
                  type="button"
                  className="btn-cancel"
                  onClick={() => setPayingBill(null)}
                  disabled={isProcessingPay}
                >
                  Cancel
                </button>
                <button type="submit" className="btn-confirm-pay" disabled={isProcessingPay}>
                  {isProcessingPay ? 'Recording...' : 'Confirm Payment'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: Void Bill */}
      {voidingBill && (
        <div className="modal-backdrop" onClick={() => !isProcessingVoid && setVoidingBill(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>Void Invoice</h3>
            <p className="modal-sub">
              Voiding invoice <strong>{voidingBill.invoiceNumber}</strong> cannot be undone.
            </p>
            <form onSubmit={handleConfirmVoid}>
              <div className="form-field">
                <label>Reason for Voiding</label>
                <textarea
                  rows="3"
                  value={voidReason}
                  onChange={(e) => setVoidReason(e.target.value)}
                  placeholder="e.g. Appointment cancelled by clinic, duplicate entry, waiver"
                  required
                />
              </div>
              <div className="modal-btn-row">
                <button
                  type="button"
                  className="btn-cancel"
                  onClick={() => setVoidingBill(null)}
                  disabled={isProcessingVoid}
                >
                  Cancel
                </button>
                <button type="submit" className="btn-confirm-void" disabled={isProcessingVoid}>
                  {isProcessingVoid ? 'Voiding...' : 'Confirm Void'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Printable Invoice Modal */}
      {selectedBill && (
        <InvoiceModal bill={selectedBill} onClose={() => setSelectedBill(null)} />
      )}
    </div>
  );
}

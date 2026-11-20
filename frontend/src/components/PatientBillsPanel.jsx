import { useState, useEffect, useCallback } from 'react';
import billService, { formatCurrency, toPaise, formatPaise } from '../api/billService';
import InvoiceModal from './InvoiceModal';
import './PatientBillsPanel.css';

export default function PatientBillsPanel() {
  const [bills, setBills] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedBill, setSelectedBill] = useState(null);

  const fetchBills = useCallback(async (signal) => {
    try {
      setLoading(true);
      setError('');
      const data = await billService.getMyBills(signal);
      setBills(data);
    } catch (err) {
      if (err.name === 'AbortError' || err.name === 'CanceledError') return;
      setError(err?.response?.data?.message || err.message || 'Failed to load your bills');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) {
        fetchBills(controller.signal);
      }
    });
    return () => controller.abort();
  }, [fetchBills]);

  const unpaidCount = bills.filter((b) => b.status === 'UNPAID').length;
  const paidCount = bills.filter((b) => b.status === 'PAID').length;
  const totalUnpaidPaise = bills
    .filter((b) => b.status === 'UNPAID')
    .reduce((sum, b) => sum + toPaise(b.amount), 0n);

  return (
    <div className="patient-bills-panel" data-testid="patient-bills-panel">
      <div className="bills-header">
        <div>
          <h2>My Bills & Invoices</h2>
          <p className="bills-subtitle">View and print invoices for your doctor consultations</p>
        </div>
        <button
          type="button"
          className="btn-refresh-bills"
          onClick={() => fetchBills()}
          disabled={loading}
        >
          {loading ? 'Refreshing...' : 'Refresh Bills'}
        </button>
      </div>

      {/* Summary Cards */}
      <div className="bills-summary-row">
        <div className="summary-card">
          <span className="summary-label">Total Invoices</span>
          <span className="summary-value" data-testid="total-invoices-count">{bills.length}</span>
        </div>
        <div className="summary-card highlight-unpaid">
          <span className="summary-label">Unpaid Invoices</span>
          <span className="summary-value" data-testid="unpaid-invoices-count">{unpaidCount}</span>
          <span className="summary-sub">Due: {formatPaise(totalUnpaidPaise)}</span>
        </div>
        <div className="summary-card highlight-paid">
          <span className="summary-label">Paid Invoices</span>
          <span className="summary-value" data-testid="paid-invoices-count">{paidCount}</span>
        </div>
      </div>

      {/* Error State */}
      {error && (
        <div className="bills-error-banner" role="alert">
          <p>{error}</p>
          <button type="button" onClick={() => fetchBills()}>Retry</button>
        </div>
      )}

      {/* Loading State */}
      {loading && !bills.length && (
        <div className="bills-loading-state" data-testid="bills-loading">
          <div className="spinner"></div>
          <p>Loading your invoices...</p>
        </div>
      )}

      {/* Empty State */}
      {!loading && !error && bills.length === 0 && (
        <div className="bills-empty-state" data-testid="bills-empty">
          <div className="empty-icon">&#128196;</div>
          <h3>No Invoices Yet</h3>
          <p>Invoices will appear here once your booked appointments are processed by hospital billing.</p>
        </div>
      )}

      {/* Bills Table */}
      {!loading && bills.length > 0 && (
        <div className="bills-table-container">
          <table className="bills-table" aria-label="My Bills Table">
            <thead>
              <tr>
                <th>Invoice #</th>
                <th>Doctor</th>
                <th>Appointment</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Date Issued</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {bills.map((bill) => (
                <tr key={bill.id} data-testid={`bill-row-${bill.id}`}>
                  <td className="cell-invoice-number">
                    <strong>{bill.invoiceNumber}</strong>
                  </td>
                  <td>
                    <div className="cell-doctor-name">{bill.doctorName}</div>
                    {bill.doctorSpecialization && (
                      <div className="cell-sub">{bill.doctorSpecialization}</div>
                    )}
                  </td>
                  <td>
                    {bill.appointmentDate ? (
                      <div>
                        {bill.appointmentDate}
                        {bill.appointmentTime ? ` at ${bill.appointmentTime}` : ''}
                      </div>
                    ) : (
                      <span className="cell-sub">Appointment #{bill.appointmentId}</span>
                    )}
                  </td>
                  <td className="cell-amount">
                    <strong>{formatCurrency(bill.amount)}</strong>
                  </td>
                  <td>
                    <span className={`status-pill status-${bill.status?.toLowerCase()}`}>
                      {bill.status}
                    </span>
                  </td>
                  <td className="cell-date">
                    {bill.issuedAt ? new Date(bill.issuedAt).toLocaleDateString() : 'N/A'}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn-view-invoice"
                      onClick={() => setSelectedBill(bill)}
                      data-testid={`view-invoice-${bill.id}`}
                    >
                      View Invoice
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Printable Invoice Modal */}
      {selectedBill && (
        <InvoiceModal bill={selectedBill} onClose={() => setSelectedBill(null)} />
      )}
    </div>
  );
}

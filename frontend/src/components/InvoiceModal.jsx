import { useEffect } from 'react';
import { formatCurrency } from '../api/billService';
import './InvoiceModal.css';

export default function InvoiceModal({ bill, onClose }) {
  useEffect(() => {
    document.body.classList.add('invoice-modal-active');
    return () => {
      document.body.classList.remove('invoice-modal-active');
    };
  }, []);

  if (!bill) return null;

  const handlePrint = () => {
    window.print();
  };

  return (
    <div className="invoice-modal-overlay" onClick={onClose} role="dialog" aria-modal="true" aria-labelledby="invoice-modal-title">
      <div className="invoice-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="invoice-modal-header no-print">
          <h2 id="invoice-modal-title">Invoice Details</h2>
          <div className="invoice-modal-actions">
            <button type="button" className="btn-print" onClick={handlePrint}>
              Print Invoice
            </button>
            <button type="button" className="btn-close" onClick={onClose} aria-label="Close invoice modal">
              &times;
            </button>
          </div>
        </div>

        {/* Printable Invoice Container */}
        <div className="printable-invoice">
          <div className="demo-notice-banner">
            <strong>DEMONSTRATION INVOICE</strong> — FOR LOCAL/DEMO USE ONLY (NOT AN OFFICIAL TAX INVOICE)
          </div>

          <div className="invoice-brand-row">
            <div>
              <h1 className="hospital-name">Hospital Management System</h1>
              <p className="hospital-tagline">Clinical Outpatient & Consultation Services</p>
            </div>
            <div className="invoice-meta-box">
              <div className="invoice-number-badge">{bill.invoiceNumber}</div>
              <div className="invoice-date">
                Issued: {bill.issuedAt ? new Date(bill.issuedAt).toLocaleDateString() : 'N/A'}
              </div>
              <div className={`invoice-status-pill status-${bill.status?.toLowerCase()}`}>
                {bill.status}
              </div>
            </div>
          </div>

          <hr className="invoice-divider" />

          <div className="invoice-info-grid">
            <div className="info-column">
              <h3>Billed To (Patient)</h3>
              <p className="info-name">{bill.patientName || 'Patient'}</p>
              {bill.patientEmail && <p className="info-detail">Email: {bill.patientEmail}</p>}
              <p className="info-detail">Patient ID: #{bill.patientId}</p>
            </div>
            <div className="info-column">
              <h3>Consultation Details</h3>
              <p className="info-name">{bill.doctorName || 'Doctor'}</p>
              {bill.doctorSpecialization && (
                <p className="info-detail">Specialization: {bill.doctorSpecialization}</p>
              )}
              {bill.appointmentDate && (
                <p className="info-detail">
                  Appointment: {bill.appointmentDate} {bill.appointmentTime || ''}
                </p>
              )}
              <p className="info-detail">Appointment ID: #{bill.appointmentId}</p>
            </div>
          </div>

          <table className="invoice-items-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Description</th>
                <th>Qty</th>
                <th>Unit Price</th>
                <th>Total</th>
              </tr>
            </thead>
            <tbody>
              {bill.items && bill.items.length > 0 ? (
                bill.items.map((item, idx) => (
                  <tr key={item.id || idx}>
                    <td>{idx + 1}</td>
                    <td>{item.description}</td>
                    <td>{item.quantity}</td>
                    <td>{formatCurrency(item.unitPrice)}</td>
                    <td>{formatCurrency(item.totalPrice)}</td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td>1</td>
                  <td>Medical Consultation Fee</td>
                  <td>1</td>
                  <td>{formatCurrency(bill.amount)}</td>
                  <td>{formatCurrency(bill.amount)}</td>
                </tr>
              )}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan="4" className="total-label">Total Amount Due ({bill.currency || 'INR'}):</td>
                <td className="total-value">{formatCurrency(bill.amount)}</td>
              </tr>
            </tfoot>
          </table>

          {bill.status === 'PAID' && (
            <div className="payment-history-box">
              <h4>Payment Record</h4>
              <p>
                <strong>Status:</strong> PAID &bull; <strong>Method:</strong> {bill.paymentMethod || 'MANUAL_DEMO'} &bull;{' '}
                <strong>Reference:</strong> {bill.paymentReference || 'N/A'}
              </p>
              {bill.paidAt && (
                <p className="payment-time">
                  Recorded: {new Date(bill.paidAt).toLocaleString()}
                  {bill.paidByAdminEmail && ` by ${bill.paidByAdminEmail}`}
                </p>
              )}
            </div>
          )}

          {bill.status === 'VOID' && (
            <div className="void-record-box">
              <h4>Void Record</h4>
              <p><strong>Reason:</strong> {bill.voidReason || 'Administrative void'}</p>
              {bill.voidedAt && (
                <p className="payment-time">
                  Voided on {new Date(bill.voidedAt).toLocaleString()}
                </p>
              )}
            </div>
          )}

          <div className="invoice-footer-note">
            <p>Thank you for choosing our hospital. For billing queries, please visit the hospital billing desk.</p>
            <p className="demo-subnote">This is a system demonstration invoice. No external payment processor used.</p>
          </div>
        </div>
      </div>
    </div>
  );
}

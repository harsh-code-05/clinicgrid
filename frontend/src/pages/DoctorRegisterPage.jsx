import { useState, useEffect } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { mutationErrorMessage } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import './RegisterPage.css';

export default function DoctorRegisterPage() {
  const { user, loading } = useAuth();
  const navigate = useNavigate();

  const [formData, setFormData] = useState({
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    specializationId: '',
    qualification: '',
    experience: '',
  });

  const [specializations, setSpecializations] = useState([]);
  const [specializationsLoading, setSpecializationsLoading] = useState(true);
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Load specializations on mount
  useEffect(() => {
    let isMounted = true;
    const loadSpecs = async () => {
      try {
        const response = await api.get('/specializations');
        if (isMounted && Array.isArray(response.data)) {
          const cleanSpecs = response.data.filter(
            (s) => s && s.name && !s.name.toLowerCase().startsWith('doc ') && !s.name.toLowerCase().includes('catalog spec') && !s.name.toLowerCase().includes('svc spec')
          );
          setSpecializations(cleanSpecs);
          if (cleanSpecs.length > 0) {
            setFormData((prev) => ({
              ...prev,
              specializationId: String(cleanSpecs[0].id),
            }));
          }
        }
      } catch {
        // Fallback: leave specializations empty
      } finally {
        if (isMounted) setSpecializationsLoading(false);
      }
    };
    loadSpecs();
    return () => {
      isMounted = false;
    };
  }, []);

  // If already logged in, redirect
  if (!loading && user) {
    return <Navigate to="/doctor" replace />;
  }

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const validateForm = () => {
    const trimmedEmail = formData.email.trim();
    if (!trimmedEmail) return 'Email is required.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      return 'Please enter a valid email address.';
    }

    if (!formData.password) return 'Password is required.';
    if (formData.password.length < 8) {
      return 'Password must be at least 8 characters long.';
    }

    if (!formData.firstName.trim()) return 'First name is required.';
    if (!formData.lastName.trim()) return 'Last name is required.';

    if (!formData.specializationId) {
      return 'Please select a medical specialization.';
    }

    if (!formData.qualification.trim()) {
      return 'Medical qualification is required (e.g. MBBS, MD).';
    }

    const exp = parseInt(formData.experience, 10);
    if (isNaN(exp) || exp < 0) {
      return 'Years of experience must be a non-negative number.';
    }

    return null;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrorMessage('');

    const validationError = validateForm();
    if (validationError) {
      setErrorMessage(validationError);
      return;
    }

    setIsSubmitting(true);

    try {
      const payload = {
        email: formData.email.trim().toLowerCase(),
        password: formData.password,
        firstName: formData.firstName.trim(),
        lastName: formData.lastName.trim(),
        specializationId: Number(formData.specializationId),
        qualification: formData.qualification.trim(),
        experience: Number(formData.experience),
      };

      const response = await api.post('/auth/register/doctor', payload);
      if (response.status === 201 || response.status === 200) {
        navigate('/login?registered=doctor', { replace: true });
      } else {
        setErrorMessage('Doctor registration could not be completed. Please try again.');
        setIsSubmitting(false);
      }
    } catch (err) {
      setIsSubmitting(false);
      if (err.response?.status === 400) {
        const errorData = err.response?.data;
        if (errorData?.errors && typeof errorData.errors === 'object') {
          const firstError = Object.values(errorData.errors)[0];
          setErrorMessage(firstError || 'Validation failed. Please verify your details.');
        } else if (errorData?.message) {
          setErrorMessage(errorData.message);
        } else {
          setErrorMessage('Invalid doctor registration details. Please check the form.');
        }
      } else if (err.response?.status === 409) {
        setErrorMessage('An account with this email address already exists. Please sign in.');
      } else {
        setErrorMessage(
          mutationErrorMessage(err, 'Unable to connect to the server. Please try again later.')
        );
      }
    }
  };

  return (
    <div className="register-page">
      <div className="register-card">
        <div className="register-header">
          <h1>Doctor Application</h1>
          <p>Join our healthcare network and connect with patients across India</p>
        </div>

        <div className="register-role-tabs" role="tablist" aria-label="Account type">
          <Link
            to="/register/patient"
            className="register-role-tab"
            role="tab"
            aria-selected="false"
          >
            Patient Registration
          </Link>
          <button
            type="button"
            className="register-role-tab active"
            role="tab"
            aria-selected="true"
          >
            Apply as Doctor
          </button>
        </div>

        <div className="register-notice" style={{ marginBottom: 20 }}>
          <strong>Notice:</strong> Doctor applications require administrative credential
          verification. After registration, you may log in to complete document verification
          under your portal. Availability scheduling will be unlocked upon approval.
        </div>

        {errorMessage && (
          <div className="register-error" role="alert">
            {errorMessage}
          </div>
        )}

        <form className="register-form" onSubmit={handleSubmit} noValidate>
          <div className="form-row">
            <div className="form-group">
              <label htmlFor="firstName">First Name *</label>
              <input
                id="firstName"
                name="firstName"
                type="text"
                className="form-input"
                value={formData.firstName}
                onChange={handleChange}
                placeholder="e.g. Gregory"
                disabled={isSubmitting}
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="lastName">Last Name *</label>
              <input
                id="lastName"
                name="lastName"
                type="text"
                className="form-input"
                value={formData.lastName}
                onChange={handleChange}
                placeholder="e.g. House"
                disabled={isSubmitting}
                required
              />
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="email">Work Email Address *</label>
            <input
              id="email"
              name="email"
              type="email"
              className="form-input"
              value={formData.email}
              onChange={handleChange}
              placeholder="doctor@hospital.org"
              disabled={isSubmitting}
              autoComplete="email"
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password (min 8 characters) *</label>
            <div className="password-input-wrapper">
              <input
                id="password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                className="form-input"
                value={formData.password}
                onChange={handleChange}
                placeholder="Choose a strong password"
                disabled={isSubmitting}
                autoComplete="new-password"
                required
              />
              <button
                type="button"
                className="password-toggle-btn"
                onClick={() => setShowPassword((prev) => !prev)}
                disabled={isSubmitting}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="specializationId">Specialization *</label>
            <select
              id="specializationId"
              name="specializationId"
              className="form-select"
              value={formData.specializationId}
              onChange={handleChange}
              disabled={isSubmitting || specializationsLoading}
              required
            >
              {specializationsLoading ? (
                <option value="">Loading specializations...</option>
              ) : specializations.length === 0 ? (
                <option value="1">General Medicine</option>
              ) : (
                specializations.map((spec) => (
                  <option key={spec.id} value={spec.id}>
                    {spec.name} {spec.description ? `— ${spec.description}` : ''}
                  </option>
                ))
              )}
            </select>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="qualification">Qualification *</label>
              <input
                id="qualification"
                name="qualification"
                type="text"
                className="form-input"
                value={formData.qualification}
                onChange={handleChange}
                placeholder="e.g. MBBS, MD, MS"
                disabled={isSubmitting}
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="experience">Years of Experience *</label>
              <input
                id="experience"
                name="experience"
                type="number"
                min="0"
                max="70"
                className="form-input"
                value={formData.experience}
                onChange={handleChange}
                placeholder="e.g. 8"
                disabled={isSubmitting}
                required
              />
            </div>
          </div>

          <button
            type="submit"
            className="register-submit-btn"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Submitting Application...' : 'Submit Doctor Application'}
          </button>
        </form>

        <div className="register-footer">
          Already registered? <Link to="/login">Sign In</Link>
        </div>
      </div>
    </div>
  );
}

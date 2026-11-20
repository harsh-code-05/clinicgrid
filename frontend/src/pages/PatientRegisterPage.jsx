import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { mutationErrorMessage } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import './RegisterPage.css';

export default function PatientRegisterPage() {
  const { user, loading } = useAuth();
  const navigate = useNavigate();

  const [formData, setFormData] = useState({
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    dateOfBirth: '',
    gender: 'MALE',
    phone: '',
  });

  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // If already logged in, redirect
  if (!loading && user) {
    return <Navigate to="/patient" replace />;
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

    if (!formData.dateOfBirth) return 'Date of birth is required.';
    const dob = new Date(formData.dateOfBirth);
    const today = new Date();
    if (isNaN(dob.getTime()) || dob >= today) {
      return 'Date of birth must be a valid date in the past.';
    }

    if (!formData.gender) return 'Gender is required.';

    const trimmedPhone = formData.phone.trim();
    if (!trimmedPhone) return 'Phone number is required.';
    if (!/^[0-9+()\-\s]{7,20}$/.test(trimmedPhone)) {
      return 'Phone number must be between 7 and 20 digits.';
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
        dateOfBirth: formData.dateOfBirth,
        gender: formData.gender,
        phone: formData.phone.trim(),
      };

      const response = await api.post('/auth/register/patient', payload);
      if (response.status === 201 || response.status === 200) {
        navigate('/login?registered=patient', { replace: true });
      } else {
        setErrorMessage('Registration could not be completed. Please try again.');
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
          setErrorMessage('Invalid registration details. Please check the form.');
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

  const todayIso = new Date().toISOString().split('T')[0];

  return (
    <div className="register-page">
      <div className="register-card">
        <div className="register-header">
          <h1>Create an Account</h1>
          <p>Register as a patient to book appointments and consult doctors</p>
        </div>

        <div className="register-role-tabs" role="tablist" aria-label="Account type">
          <button
            type="button"
            className="register-role-tab active"
            role="tab"
            aria-selected="true"
          >
            Patient Registration
          </button>
          <Link
            to="/register/doctor"
            className="register-role-tab"
            role="tab"
            aria-selected="false"
          >
            Apply as Doctor
          </Link>
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
                placeholder="e.g. John"
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
                placeholder="e.g. Doe"
                disabled={isSubmitting}
                required
              />
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="email">Email Address *</label>
            <input
              id="email"
              name="email"
              type="email"
              className="form-input"
              value={formData.email}
              onChange={handleChange}
              placeholder="name@example.com"
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

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="dateOfBirth">Date of Birth *</label>
              <input
                id="dateOfBirth"
                name="dateOfBirth"
                type="date"
                max={todayIso}
                className="form-input"
                value={formData.dateOfBirth}
                onChange={handleChange}
                disabled={isSubmitting}
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="gender">Gender *</label>
              <select
                id="gender"
                name="gender"
                className="form-select"
                value={formData.gender}
                onChange={handleChange}
                disabled={isSubmitting}
                required
              >
                <option value="MALE">Male</option>
                <option value="FEMALE">Female</option>
                <option value="OTHER">Other</option>
              </select>
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="phone">Phone Number *</label>
            <input
              id="phone"
              name="phone"
              type="tel"
              className="form-input"
              value={formData.phone}
              onChange={handleChange}
              placeholder="+91-9876543210"
              disabled={isSubmitting}
              autoComplete="tel"
              required
            />
          </div>

          <button
            type="submit"
            className="register-submit-btn"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Creating Patient Account...' : 'Complete Patient Registration'}
          </button>
        </form>

        <div className="register-footer">
          Already have an account? <Link to="/login">Sign In</Link>
        </div>
      </div>
    </div>
  );
}

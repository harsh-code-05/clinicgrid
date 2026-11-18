package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.AdminUserRequest;
import com.harsh.hospitalmanagement.dto.UserRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this(userRepository, null, passwordEncoder);
    }

    @Autowired
    public UserService(
            UserRepository userRepository,
            @Autowired(required = false) PatientRepository patientRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.patientRepository = patientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User getUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResourceNotFoundException("User not found");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));
    }

    public User createUser(UserRequest request) {

        if (request.getRole() != Role.PATIENT) {
            throw new ForbiddenException(
                    "Public registration is allowed only for PATIENT role");
        }

        String trimmed = request.getEmail().trim();
        String normalizedEmail = trimmed.toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(trimmed) || userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BadRequestException("Email already exists");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());

        return userRepository.save(user);
    }

    public User createStaffUser(AdminUserRequest request) {

        if (request.getRole() != Role.DOCTOR &&
                request.getRole() != Role.ADMIN) {

            throw new BadRequestException(
                    "Staff role must be DOCTOR or ADMIN");
        }

        String trimmed = request.getEmail().trim();
        String normalizedEmail = trimmed.toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(trimmed) || userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BadRequestException("Email already exists");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());

        return userRepository.save(user);
    }

    public User findOrCreateGoogleUser(
            String email,
            String googleId) {

        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required");
        }
        if (googleId == null || googleId.isBlank()) {
            throw new BadRequestException("Google ID is required");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String cleanGoogleId = googleId.trim();

        // 1. Check if user already exists with this googleId
        Optional<User> byGoogleId = userRepository.findByGoogleId(cleanGoogleId);
        if (byGoogleId.isPresent()) {
            return byGoogleId.get();
        }

        // 2. Check if an account already exists with this verified email -> safely link Google identity
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (byEmail.isPresent()) {
            User existingUser = byEmail.get();
            if (existingUser.getGoogleId() != null && !existingUser.getGoogleId().equals(cleanGoogleId)) {
                throw new BadRequestException("This account is already linked to a different Google account");
            }
            existingUser.setGoogleId(cleanGoogleId);
            return userRepository.save(existingUser);
        }

        // 3. New Google user -> default strictly to PATIENT (never DOCTOR or ADMIN)
        User newUser = new User();
        newUser.setEmail(normalizedEmail);
        newUser.setPassword(null);
        newUser.setRole(Role.PATIENT);
        newUser.setGoogleId(cleanGoogleId);
        User savedUser = userRepository.save(newUser);

        // Safely provision a default Patient profile if patientRepository is available
        if (patientRepository != null && patientRepository.findByUser_Id(savedUser.getId()).isEmpty()) {
            Patient patient = new Patient();
            patient.setUser(savedUser);
            patient.setFirstName("Google");
            patient.setLastName("User");
            patientRepository.save(patient);
        }

        return savedUser;
    }
}

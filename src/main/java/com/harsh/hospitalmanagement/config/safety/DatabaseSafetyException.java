package com.harsh.hospitalmanagement.config.safety;

/**
 * Thrown when database configuration violates the local-first development safety constraints.
 */
public class DatabaseSafetyException extends IllegalStateException {

    public DatabaseSafetyException(String message) {
        super(message);
    }

    public DatabaseSafetyException(String message, Throwable cause) {
        super(message, cause);
    }
}

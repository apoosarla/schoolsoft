package com.schoolsoft.platform.web;

/**
 * The request is well-formed but the current state refuses it — a register
 * already signed off, a cover already assigned. Distinct from a bad request:
 * the caller is not wrong, they are late.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}

package com.schoolsoft.platform.web;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}

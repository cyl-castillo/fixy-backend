package com.fixy.backend.dto;

/** ID token JWT ("credential") emitido por Google Identity Services en el frontend. */
public record GoogleLoginRequest(String credential) {
}

package com.fixy.backend.dto;

public record GoogleLoginResponse(String sessionToken, AppUserSummary user) {

  public record AppUserSummary(String name, String email, String pictureUrl) {
  }
}

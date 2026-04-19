package com.cortex.cortex_gateway.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/auth")
public class AuthController {

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal OAuth2User user) {
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    return ResponseEntity.ok(Map.of(
        "userId", user.getAttribute("sub"),
        "email", user.getAttribute("email"),
        "name", user.getAttribute("name"),
        "picture", user.getAttribute("picture")));
  }

}

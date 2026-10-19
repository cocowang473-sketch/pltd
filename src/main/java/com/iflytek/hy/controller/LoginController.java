package com.iflytek.hy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login controller for user authentication.
 * Stores user credentials in memory for demo purposes.
 */
@RestController
public class LoginController {

    // In-memory user store (username -> password)
    // In production, this should be replaced with a database
    private static final Map<String, String> USERS = new ConcurrentHashMap<>();

    static {
        // Default users with secure passwords
        USERS.put("admin", "PLDT@dm!n2026");
        USERS.put("support", "Supp0rt#PLDT");
        USERS.put("agent", "Ag3nt$ecure!");
    }

    @PostMapping("/api/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Username is required"));
        }

        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password is required"));
        }

        // Check credentials
        String storedPassword = USERS.get(username.trim());
        if (storedPassword == null || !storedPassword.equals(password)) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Invalid username or password"));
        }

        // Create session
        HttpSession session = request.getSession(true);
        session.setAttribute("loggedIn", true);
        session.setAttribute("username", username.trim());

        return ResponseEntity.ok(Map.of("success", true, "username", username.trim()));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/api/user")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("loggedIn") != null
                && (Boolean) session.getAttribute("loggedIn")) {
            String username = (String) session.getAttribute("username");
            return ResponseEntity.ok(Map.of("loggedIn", true, "username", username));
        }
        return ResponseEntity.status(401).body(Map.of("loggedIn", false));
    }

    /**
     * Add a new user (for demo purposes, no authentication required)
     */
    @PostMapping("/api/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Username is required"));
        }

        if (password == null || password.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password must be at least 4 characters"));
        }

        if (USERS.containsKey(username.trim())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Username already exists"));
        }

        USERS.put(username.trim(), password);
        return ResponseEntity.ok(Map.of("success", true, "message", "User registered successfully"));
    }
}
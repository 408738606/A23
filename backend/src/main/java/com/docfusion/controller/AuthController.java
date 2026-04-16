package com.docfusion.controller;

import com.docfusion.model.UserAccount;
import com.docfusion.model.UserAccountRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    private UserAccountRepo userAccountRepo;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> req) {
        try {
            String username = normalize(req.get("username"));
            String password = normalize(req.get("password"));
            String displayName = normalize(req.get("displayName"));
            if (username.isBlank() || password.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名和密码不能为空"));
            }
            if (username.length() < 3 || username.length() > 32) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名长度需在3-32之间"));
            }
            if (password.length() < 6 || password.length() > 64) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "密码长度需在6-64之间"));
            }
            if (userAccountRepo.findByUsername(username).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名已存在"));
            }
            UserAccount user = new UserAccount();
            user.setUsername(username);
            user.setPasswordHash(sha256(password));
            user.setDisplayName(displayName.isBlank() ? username : displayName);
            user.setAvatarUrl("");
            user.setBio("");
            user.setAuthToken(generateToken());
            userAccountRepo.save(user);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "注册成功",
                    "data", sanitizeUser(user),
                    "token", user.getAuthToken()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> req) {
        String username = normalize(req.get("username"));
        String password = normalize(req.get("password"));
        if (username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名和密码不能为空"));
        }
        return userAccountRepo.findByUsername(username)
                .map(user -> {
                    if (!sha256(password).equals(user.getPasswordHash())) {
                        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名或密码错误"));
                    }
                    user.setAuthToken(generateToken());
                    user.setUpdatedAt(LocalDateTime.now());
                    userAccountRepo.save(user);
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "message", "登录成功",
                            "data", sanitizeUser(user),
                            "token", user.getAuthToken()
                    ));
                })
                .orElse(ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名或密码错误")));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UserAccount user = resolveByAuthorization(authorization);
        if (user != null) {
            user.setAuthToken(null);
            userAccountRepo.save(user);
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "已退出登录"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UserAccount user = resolveByAuthorization(authorization);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录或登录已失效"));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", sanitizeUser(user)));
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, String> req) {
        UserAccount user = resolveByAuthorization(authorization);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "未登录或登录已失效"));
        }
        String displayName = normalize(req.get("displayName"));
        String avatarUrl = normalize(req.get("avatarUrl"));
        String bio = normalize(req.get("bio"));
        if (!displayName.isBlank()) {
            if (displayName.length() > 64) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "昵称长度不能超过64"));
            }
            user.setDisplayName(displayName);
        }
        if (avatarUrl.length() > 1024) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "头像地址过长"));
        }
        if (bio.length() > 512) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "个人简介不能超过512"));
        }
        user.setAvatarUrl(avatarUrl);
        user.setBio(bio);
        userAccountRepo.save(user);
        return ResponseEntity.ok(Map.of("success", true, "message", "个人信息已更新", "data", sanitizeUser(user)));
    }

    private UserAccount resolveByAuthorization(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        String prefix = "Bearer ";
        String token = authorization.startsWith(prefix) ? authorization.substring(prefix.length()).trim() : authorization.trim();
        if (token.isBlank()) return null;
        return userAccountRepo.findByAuthToken(token).orElse(null);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String generateToken() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("密码处理失败");
        }
    }

    private Map<String, Object> sanitizeUser(UserAccount user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
                "bio", user.getBio() == null ? "" : user.getBio(),
                "createdAt", user.getCreatedAt(),
                "updatedAt", user.getUpdatedAt()
        );
    }
}

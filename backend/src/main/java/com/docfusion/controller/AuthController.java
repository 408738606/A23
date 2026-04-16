package com.docfusion.controller;

import com.docfusion.model.UserAccount;
import com.docfusion.model.UserAccountRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final int PBKDF2_ITERATIONS = 600000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int TOKEN_EXPIRE_DAYS = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            user.setPasswordHash(hashPassword(password));
            user.setDisplayName(displayName.isBlank() ? username : displayName);
            user.setAvatarUrl("");
            user.setBio("");
            user.setAuthToken(generateToken());
            user.setTokenExpiresAt(LocalDateTime.now().plusDays(TOKEN_EXPIRE_DAYS));
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
                    if (!verifyPassword(password, user.getPasswordHash())) {
                        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名或密码错误"));
                    }
                    user.setAuthToken(generateToken());
                    user.setTokenExpiresAt(LocalDateTime.now().plusDays(TOKEN_EXPIRE_DAYS));
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
            user.setTokenExpiresAt(null);
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
        UserAccount user = userAccountRepo.findByAuthToken(token).orElse(null);
        if (user == null) return null;
        LocalDateTime expiresAt = user.getTokenExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
            user.setAuthToken(null);
            user.setTokenExpiresAt(null);
            userAccountRepo.save(user);
            return null;
        }
        return user;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashPassword(String plainPassword) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            SECURE_RANDOM.nextBytes(salt);
            byte[] hash = pbkdf2(plainPassword.toCharArray(), salt);
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("密码处理失败");
        }
    }

    private boolean verifyPassword(String plainPassword, String stored) {
        if (stored == null || !stored.contains(":")) return false;
        try {
            String[] parts = stored.split(":", 2);
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = pbkdf2(plainPassword.toCharArray(), salt);
            if (actual.length != expected.length) return false;
            int diff = 0;
            for (int i = 0; i < actual.length; i++) diff |= (actual[i] ^ expected[i]);
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BYTES * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
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

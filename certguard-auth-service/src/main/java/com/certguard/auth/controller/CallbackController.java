package com.certguard.auth.controller;

import com.certguard.auth.dto.response.TokenResponse;
import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.service.GoogleAuthService;
import com.certguard.auth.service.MicrosoftAuthService;
import com.certguard.auth.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles the OAuth redirect-back from Google and Microsoft.
 *
 * Register these URIs in your OAuth app consoles:
 *   Google:    http://localhost:8090/api/auth/callback/google
 *   Microsoft: http://localhost:8090/api/auth/callback/microsoft
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/callback")
@RequiredArgsConstructor
public class CallbackController {

    private final GoogleAuthService googleAuthService;
    private final MicrosoftAuthService microsoftAuthService;
    private final TokenService tokenService;

    @Value("${auth.callback.base-url:http://localhost:8090}")
    private String baseUrl;

    /**
     * GET /api/auth/callback/google?code=xxx&state=yyy
     *
     * Google redirects here after the user authenticates.
     * Exchanges the code, creates a session, and returns the JWT as JSON.
     */
    @GetMapping(value = "/google", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> googleCallback(
            @RequestParam String code,
            @RequestParam(required = false) String error,
            HttpServletRequest req) {

        if (error != null) {
            log.warn("Google OAuth error: {}", error);
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorPage("Google login failed: " + error));
        }

        String redirectUri = baseUrl + "/api/auth/callback/google";
        User user = googleAuthService.authenticateWithCode(code, redirectUri);
        TokenResponse token = tokenService.createSession(user, "google", clientIp(req));

        log.info("Google OAuth callback success for {}", user.getEmail());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(successPage(token));
    }

    /**
     * GET /api/auth/callback/microsoft?code=xxx
     *
     * Microsoft redirects here after the user authenticates.
     * Exchanges the code, creates a session, and returns the JWT as JSON.
     */
    @GetMapping(value = "/microsoft", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> microsoftCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletRequest req) {

        if (error != null) {
            log.warn("Microsoft OAuth error: {} — {}", error, errorDescription);
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorPage("Microsoft login failed: " + errorDescription));
        }
        if (code == null) {
            throw new AuthException("No authorization code received from Microsoft");
        }

        String redirectUri = baseUrl + "/api/auth/callback/microsoft";
        User user = microsoftAuthService.authenticateWithCode(code, redirectUri);
        TokenResponse token = tokenService.createSession(user, "microsoft", clientIp(req));

        log.info("Microsoft OAuth callback success for {}", user.getEmail());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(successPage(token));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }

    /** Simple HTML page that displays the token and lets you copy it. */
    private String successPage(TokenResponse t) {
        return """
                <!DOCTYPE html>
                <html>
                <head><title>Login Successful</title>
                <style>
                  body { font-family: monospace; padding: 2rem; background: #f0f4f8; }
                  .card { background: white; padding: 1.5rem; border-radius: 8px;
                          box-shadow: 0 2px 8px rgba(0,0,0,.1); max-width: 800px; }
                  h2 { color: #2d7a2d; }
                  textarea { width: 100%%; height: 120px; font-size: 0.8rem; }
                  .meta { color: #555; margin-top: 1rem; }
                  button { margin-top: .5rem; padding: .4rem 1rem; cursor: pointer; }
                </style></head>
                <body>
                <div class="card">
                  <h2>&#10003; Login successful</h2>
                  <p>Copy your token to use in API calls:</p>
                  <textarea id="tok">%s</textarea>
                  <button onclick="navigator.clipboard.writeText(document.getElementById('tok').value)">Copy</button>
                  <div class="meta">
                    <b>User:</b> %s &nbsp;|&nbsp;
                    <b>Provider:</b> %s &nbsp;|&nbsp;
                    <b>Expires in:</b> %d seconds
                  </div>
                  <hr/>
                  <p>Test it now — paste in terminal:</p>
                  <textarea>curl -s http://localhost:8090/api/users/me -H "Authorization: Bearer %s" | jq .</textarea>
                </div>
                </body></html>
                """.formatted(t.token(), t.email(), t.provider(), t.expiresIn(), t.token());
    }

    private String errorPage(String message) {
        return """
                <!DOCTYPE html>
                <html>
                <head><title>Login Failed</title></head>
                <body style="font-family:monospace;padding:2rem">
                  <h2 style="color:red">&#10007; %s</h2>
                  <p><a href="javascript:history.back()">Go back</a></p>
                </body>
                </html>
                """.formatted(message);
    }
}

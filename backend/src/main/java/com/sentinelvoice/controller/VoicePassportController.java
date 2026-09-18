package com.sentinelvoice.controller;

import com.sentinelvoice.model.CallerProfile;
import com.sentinelvoice.service.VoicePassportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/passport")
public class VoicePassportController {

    private final VoicePassportService voicePassportService;

    public VoicePassportController(VoicePassportService voicePassportService) {
        this.voicePassportService = voicePassportService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerProfile(@RequestBody CallerProfile profile) {
        CallerProfile saved = voicePassportService.save(profile);
        return ResponseEntity.ok(Map.of(
                "status", "registered",
                "profileId", saved.getId(),
                "callerId", saved.getCallerId()
        ));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable Long profileId) {
        voicePassportService.deleteById(profileId);
        return ResponseEntity.ok(Map.of("status", "deleted", "profileId", profileId));
    }
}

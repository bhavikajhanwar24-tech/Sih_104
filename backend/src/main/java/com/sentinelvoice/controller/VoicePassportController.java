package com.sentinelvoice.controller;

import com.sentinelvoice.model.CallerProfile;
import com.sentinelvoice.repository.CallerProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/passport")
public class VoicePassportController {

    private final CallerProfileRepository callerProfileRepository;

    public VoicePassportController(CallerProfileRepository callerProfileRepository) {
        this.callerProfileRepository = callerProfileRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerProfile(@RequestBody CallerProfile profile) {
        CallerProfile saved = callerProfileRepository.save(profile);
        return ResponseEntity.ok(Map.of(
                "status", "registered",
                "profileId", saved.getId(),
                "callerId", saved.getCallerId()
        ));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable Long profileId) {
        callerProfileRepository.deleteById(profileId);
        return ResponseEntity.ok(Map.of("status", "deleted", "profileId", profileId));
    }
}

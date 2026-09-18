package com.sentinelvoice.controller;

import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.passport.PassportDtos;
import com.sentinelvoice.passport.VoicePassportService;
import com.sentinelvoice.passport.model.ConsentRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/passport")
public class VoicePassportController {

    private final VoicePassportService voicePassportService;

    public VoicePassportController(VoicePassportService voicePassportService) {
        this.voicePassportService = voicePassportService;
    }

    @PostMapping("/consent")
    public ResponseEntity<Map<String, Object>> grantConsent(@RequestBody PassportDtos.ConsentRequest request) {
        ConsentRecord record = voicePassportService.grantConsent(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "granted");
        body.put("consentId", record.getId());
        body.put("employeeId", record.getEmployeeId());
        body.put("purpose", record.getPurpose());
        body.put("noticeVersion", record.getNoticeVersion());
        body.put("grantedAt", record.getGrantedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/enrol")
    public ResponseEntity<PassportDtos.EnrolResponse> enrol(@RequestBody PassportDtos.EnrolRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(voicePassportService.enrol(request));
    }

    /**
     * DPDP §11 — metadata and processing log only. The embedding vector is never returned.
     */
    @GetMapping("/{profileId}")
    public ResponseEntity<PassportDtos.PassportMetadata> get(@PathVariable String profileId) {
        return ResponseEntity.ok(voicePassportService.getMetadata(profileId));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<PassportDtos.DeletionCertificate> erase(@PathVariable String profileId) {
        return ResponseEntity.ok(voicePassportService.erase(profileId));
    }

    @PostMapping("/verify")
    public ResponseEntity<PassportDtos.VerifyResult> verify(@RequestBody Map<String, Object> body) {
        String employeeId = String.valueOf(body.get("employeeId"));
        ChannelProfile profile = ChannelProfile.valueOf(String.valueOf(body.get("channelProfile")));
        Object embNode = body.get("embedding");
        float[] embedding;
        if (embNode instanceof String s) {
            embedding = com.sentinelvoice.passport.EmbeddingCodec.fromBase64(s);
        } else if (embNode instanceof java.util.List<?> list) {
            embedding = new float[com.sentinelvoice.passport.EmbeddingCodec.DIM];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = ((Number) list.get(i)).floatValue();
            }
        } else {
            throw new IllegalArgumentException("embedding must be base64 string or float array");
        }
        return ResponseEntity.ok(voicePassportService.verify(employeeId, embedding, profile));
    }
}

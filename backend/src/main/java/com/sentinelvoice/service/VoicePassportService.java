package com.sentinelvoice.service;

import com.sentinelvoice.model.CallerProfile;
import com.sentinelvoice.repository.CallerProfileRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class VoicePassportService {

    private final CallerProfileRepository callerProfileRepository;

    public VoicePassportService(CallerProfileRepository callerProfileRepository) {
        this.callerProfileRepository = callerProfileRepository;
    }

    public Optional<CallerProfile> findByCallerId(String callerId) {
        return callerProfileRepository.findByCallerId(callerId);
    }

    public CallerProfile save(CallerProfile profile) {
        return callerProfileRepository.save(profile);
    }

    public void deleteById(Long profileId) {
        callerProfileRepository.deleteById(profileId);
    }
}

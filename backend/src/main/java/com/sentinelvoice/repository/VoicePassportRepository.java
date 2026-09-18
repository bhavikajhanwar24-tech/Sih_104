package com.sentinelvoice.repository;

import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.passport.model.VoicePassport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VoicePassportRepository extends JpaRepository<VoicePassport, String> {

    Optional<VoicePassport> findByEmployeeIdAndChannelProfileAndActiveTrue(
            String employeeId,
            ChannelProfile channelProfile
    );

    List<VoicePassport> findByEmployeeIdAndActiveTrue(String employeeId);

    Optional<VoicePassport> findByProfileIdAndActiveTrue(String profileId);

    long countByActiveTrue();
}

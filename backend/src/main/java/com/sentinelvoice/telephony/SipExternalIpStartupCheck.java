package com.sentinelvoice.telephony;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loud startup signal when {@code SIP_EXTERNAL_IP} is missing (softphones / NAT break).
 */
@Component
public class SipExternalIpStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SipExternalIpStartupCheck.class);

    private final String sipExternalIp;

    public SipExternalIpStartupCheck(@Value("${SIP_EXTERNAL_IP:}") String sipExternalIp) {
        this.sipExternalIp = sipExternalIp == null ? "" : sipExternalIp.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (sipExternalIp.isBlank()) {
            log.error(
                    "SIP_EXTERNAL_IP is empty — softphone NAT will fail. "
                            + "Set SIP_EXTERNAL_IP in repo .env (see .\\scripts\\detect-lan-ip.ps1), "
                            + "restart the Decision Plane, then recreate asterisk so pjsip.conf gets the LAN IP."
            );
        } else {
            log.info("SIP_EXTERNAL_IP={}", sipExternalIp);
        }
    }
}

/**
 * Senior Shield copy — English, Hindi, Tamil, Telugu.
 * Safety strings are human-reviewed for this lab; keep wording simple and literal.
 */

/** @typedef {'en'|'hi'|'ta'|'te'} ShieldLang */

export const SHIELD_LANGS = Object.freeze([
  { id: /** @type {ShieldLang} */ ('en'), label: 'English', native: 'English' },
  { id: /** @type {ShieldLang} */ ('hi'), label: 'Hindi', native: 'हिन्दी' },
  { id: /** @type {ShieldLang} */ ('ta'), label: 'Tamil', native: 'தமிழ்' },
  { id: /** @type {ShieldLang} */ ('te'), label: 'Telugu', native: 'తెలుగు' },
]);

/** @type {Record<ShieldLang, Record<string, string>>} */
export const SHIELD_COPY = Object.freeze({
  en: Object.freeze({
    appTitle: 'Call Safety',
    statusNormal: 'This call looks normal',
    statusCaution: 'Be careful',
    statusDanger: 'STOP — this may be a fake voice',
    statusHintNormal: 'You can keep talking.',
    statusHintCaution: 'Do not share OTPs or bank details.',
    statusHintDanger: 'Hang up now. Do not send money.',
    alertTitle: 'STOP',
    alertBody: 'This may be a fake voice.',
    alertInstruction: 'DO NOT SEND MONEY. Hang up and call your family.',
    alertSpoken:
      'Stop. This may be a fake voice. Do not send money. Hang up and call your family.',
    safeWordBanner: 'Agree a family password. Ask for it if a call feels wrong.',
    sosLabel: 'Alert My Family',
    sosConfirmTitle: 'Family alerted',
    sosConfirmBody:
      'Your mother received a suspicious call at {time} that our system flagged as a possible fake voice.',
    sosContact: 'Sent to: Priya (daughter) · +91-98XXX-XX210',
    startCall: 'Start protection',
    listening: 'Listening to this call…',
    waiting: 'Waiting for a call',
    language: 'Language',
  }),
  hi: Object.freeze({
    appTitle: 'कॉल सुरक्षा',
    statusNormal: 'यह कॉल सामान्य लगती है',
    statusCaution: 'सावधान रहें',
    statusDanger: 'रुकें — यह नकली आवाज़ हो सकती है',
    statusHintNormal: 'आप बात जारी रख सकते हैं।',
    statusHintCaution: 'OTP या बैंक विवरण साझा न करें।',
    statusHintDanger: 'अभी फ़ोन काट दें। पैसे न भेजें।',
    alertTitle: 'रुकें',
    alertBody: 'यह नकली आवाज़ हो सकती है।',
    alertInstruction: 'पैसे मत भेजें। फ़ोन काटें और अपने परिवार को कॉल करें।',
    alertSpoken:
      'रुकें। यह नकली आवाज़ हो सकती है। पैसे मत भेजें। फ़ोन काटें और परिवार को कॉल करें।',
    safeWordBanner: 'परिवार का पासवर्ड तय करें। अगर कॉल गलत लगे तो पूछें।',
    sosLabel: 'परिवार को सूचित करें',
    sosConfirmTitle: 'परिवार को सूचना भेज दी गई',
    sosConfirmBody:
      'आपकी माँ को {time} पर एक संदिग्ध कॉल आई, जिसे हमारे सिस्टम ने संभावित नकली आवाज़ माना।',
    sosContact: 'भेजा गया: प्रिया (बेटी) · +91-98XXX-XX210',
    startCall: 'सुरक्षा शुरू करें',
    listening: 'इस कॉल को सुन रहे हैं…',
    waiting: 'कॉल की प्रतीक्षा',
    language: 'भाषा',
  }),
  ta: Object.freeze({
    appTitle: 'அழைப்பு பாதுகாப்பு',
    statusNormal: 'இந்த அழைப்பு இயல்பாகத் தெரிகிறது',
    statusCaution: 'கவனமாக இருங்கள்',
    statusDanger: 'நிறுத்துங்கள் — இது போலி குரல் இருக்கலாம்',
    statusHintNormal: 'நீங்கள் பேசலாம்.',
    statusHintCaution: 'OTP அல்லது வங்கி விவரங்களைப் பகிர வேண்டாம்.',
    statusHintDanger: 'இப்போதே துண்டியுங்கள். பணம் அனுப்ப வேண்டாம்.',
    alertTitle: 'நிறுத்துங்கள்',
    alertBody: 'இது போலி குரல் இருக்கலாம்.',
    alertInstruction: 'பணம் அனுப்ப வேண்டாம். அழைப்பை துண்டித்து குடும்பத்தை அழைக்கவும்.',
    alertSpoken:
      'நிறுத்துங்கள். இது போலி குரல் இருக்கலாம். பணம் அனுப்ப வேண்டாம். அழைப்பை துண்டித்து குடும்பத்தை அழைக்கவும்.',
    safeWordBanner:
      'குடும்ப கடவுச்சொல்லை ஒப்புக்கொள்ளுங்கள். அழைப்பு சந்தேகமாக இருந்தால் கேளுங்கள்.',
    sosLabel: 'குடும்பத்திற்கு அறிவிக்கவும்',
    sosConfirmTitle: 'குடும்பத்திற்கு அறிவிக்கப்பட்டது',
    sosConfirmBody:
      'உங்கள் தாய்க்கு {time} மணிக்கு சந்தேக அழைப்பு வந்தது; இது போலி குரலாக இருக்கலாம் என எங்கள் அமைப்பு குறித்தது.',
    sosContact: 'அனுப்பியது: பிரியா (மகள்) · +91-98XXX-XX210',
    startCall: 'பாதுகாப்பைத் தொடங்கு',
    listening: 'இந்த அழைப்பைக் கேட்கிறது…',
    waiting: 'அழைப்புக்காக காத்திருக்கிறது',
    language: 'மொழி',
  }),
  te: Object.freeze({
    appTitle: 'కాల్ రక్షణ',
    statusNormal: 'ఈ కాల్ సాధారణంగా కనిపిస్తోంది',
    statusCaution: 'జాగ్రత్తగా ఉండండి',
    statusDanger: 'ఆపండి — ఇది నకిలీ గొంతు కావచ్చు',
    statusHintNormal: 'మీరు మాట్లాడుతూ ఉండవచ్చు.',
    statusHintCaution: 'OTP లేదా బ్యాంక్ వివరాలు చెప్పవద్దు.',
    statusHintDanger: 'ఇప్పుడే కాల్ కట్ చేయండి. డబ్బు పంపవద్దు.',
    alertTitle: 'ఆపండి',
    alertBody: 'ఇది నకిలీ గొంతు కావచ్చు.',
    alertInstruction: 'డబ్బు పంపవద్దు. కాల్ కట్ చేసి మీ కుటుంబానికి కాల్ చేయండి.',
    alertSpoken:
      'ఆపండి. ఇది నకిలీ గొంతు కావచ్చు. డబ్బు పంపవద్దు. కాల్ కట్ చేసి కుటుంబానికి కాల్ చేయండి.',
    safeWordBanner:
      'కుటుంబ పాస్‌వర్డ్ అంగీకరించండి. కాల్ తప్పుగా అనిపిస్తే అడగండి.',
    sosLabel: 'కుటుంబానికి తెలియజేయండి',
    sosConfirmTitle: 'కుటుంబానికి సమాచారం పంపబడింది',
    sosConfirmBody:
      'మీ అమ్మకు {time}కి అనుమానాస్పద కాల్ వచ్చింది; ఇది నకిలీ గొంతు కావచ్చని మా వ్యవస్థ గుర్తించింది.',
    sosContact: 'పంపినది: ప్రియా (కూతురు) · +91-98XXX-XX210',
    startCall: 'రక్షణ ప్రారంభించండి',
    listening: 'ఈ కాల్‌ను వింటున్నాం…',
    waiting: 'కాల్ కోసం వేచి ఉన్నాం',
    language: 'భాష',
  }),
});

/**
 * @param {ShieldLang} lang
 * @param {string} key
 * @param {Record<string, string>} [vars]
 */
export function t(lang, key, vars = {}) {
  const table = SHIELD_COPY[lang] ?? SHIELD_COPY.en;
  let s = table[key] ?? SHIELD_COPY.en[key] ?? key;
  for (const [k, v] of Object.entries(vars)) {
    s = s.replaceAll(`{${k}}`, v);
  }
  return s;
}

/**
 * Map telemetry → three shield states. Thresholds are internal only — never shown in UI.
 *
 * @param {import('@/contracts').TelemetryFrame | null | undefined} frame
 * @returns {'normal'|'caution'|'danger'}
 */
export function shieldStateFromFrame(frame) {
  if (!frame) return 'normal';
  const riskState = frame?.risk?.state;
  if (riskState === 'INSUFFICIENT_EVIDENCE') return 'normal';

  const level = String(frame?.intervention?.level ?? '');
  if (
    level.includes('LEVEL_4') ||
    level.includes('LEVEL_5') ||
    level.includes('TERMINATE') ||
    level.includes('AUTO_HOLD')
  ) {
    return 'danger';
  }
  if (level.includes('LEVEL_3') || level.includes('LEVEL_2')) {
    return 'caution';
  }

  const smoothed =
    typeof frame?.risk?.smoothed === 'number' ? frame.risk.smoothed : 0;
  // Internal cut-points only (never rendered as digits in the UI).
  if (smoothed >= 0.75) return 'danger';
  if (smoothed >= 0.35) return 'caution';
  return 'normal';
}

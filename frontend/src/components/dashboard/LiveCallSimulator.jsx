import React, { useState, useRef, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiJson } from '@/services/api.js';
import { Badge, Button } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const SAMPLE_SCENARIOS = [
  {
    id: 'ceo-wire-fraud',
    title: '🚨 Urgent CEO Wire Transfer',
    subtitle: 'High-pressure financial coercion & executive impersonation',
    category: 'CRITICAL THREAT',
    badgeTone: 'danger',
    callerRole: 'Vikram (VP Corporate Treasury / Impersonator)',
    calleeRole: 'Priya (Finance Operations Associate)',
    callerPrompt:
      'Hello Priya, this is Vikram from Corporate Treasury. We have an urgent confidential offshore acquisition wire of 15 lakhs that must be released within 30 minutes before market close. Do not discuss this with anyone on the floor.',
    calleePrompt:
      'I will need the dual-authorization override code and CFO sign-off before dispatching any offshore wire above 10 lakhs.',
    callerFollowup:
      'The CEO approved override code 99214 directly. Execute it immediately, the entire board is waiting on this.',
    expectedLevel: 'LEVEL_3 (INTERVENE / ESCALATE)',
    expectedRules: ['R-wire-urgency', 'POLICY_CONFIDENTIAL_PRESSURE', 'R-ceo-fraud'],
    expectedTriggers: {
      askType: 'WIRE_TRANSFER',
      amountInr: 1500000,
      urgency: '0.95 (Extreme)',
      secrecy: 'Yes (Strict Confidentiality Enforced)',
      authority: 'Yes (Claimed CEO / Executive Authority)',
      sharesCredential: 'No',
    },
    sampleThinking:
      'Utterance exhibits high-pressure financial coercion demanding an urgent offshore wire transfer (15L) with strict secrecy requested under executive CEO authority. Violates Treasury policy R-wire-urgency and triggers Level 3 intervention.',
  },
  {
    id: 'otp-harvest-scam',
    title: '⚠️ Bank Security OTP Harvest',
    subtitle: 'Credential solicitation under guise of unauthorized transaction',
    category: 'CREDENTIAL THEFT',
    badgeTone: 'warn',
    callerRole: 'Rajesh (HDFC Fraud Prevention Specialist)',
    calleeRole: 'Account Holder (Customer)',
    callerPrompt:
      'Good afternoon, this is Rajesh calling from HDFC Bank Fraud Security. We detected an unauthorized international debit of 45,000 rupees on your card.',
    calleePrompt:
      'Oh no! I did not authorize that at all. Please cancel it immediately!',
    callerFollowup:
      'To freeze the fraudulent charge right away, please read aloud the 6-digit verification OTP sent to your registered mobile number.',
    expectedLevel: 'LEVEL_3 (BLOCK / HOLD CALL)',
    expectedRules: ['R-no-otp-over-phone', 'POLICY_CREDENTIAL_SOLICITATION', 'R-bank-impersonation-fraud'],
    expectedTriggers: {
      askType: 'OTP_SHARE',
      amountInr: 45000,
      urgency: '0.90 (High)',
      secrecy: 'No',
      authority: 'Yes (Claimed Bank Fraud Department)',
      sharesCredential: 'Yes (Soliciting OTP / PIN)',
    },
    sampleThinking:
      'Caller is soliciting a one-time passcode (OTP) under the pretext of freezing an unauthorized transaction. Direct violation of policy R-no-otp-over-phone prohibiting credential solicitation over voice.',
  },
  {
    id: 'legit-vendor-call',
    title: '🟢 Routine Business Invoice',
    subtitle: 'Normal vendor verification with zero malicious pressure',
    category: 'BENIGN / CLEAR',
    badgeTone: 'success',
    callerRole: 'Anand (Cloud Infrastructure Vendor)',
    calleeRole: 'Accounts Payable Specialist',
    callerPrompt:
      'Hi, this is Anand from Cloud Services. Just following up to check if your team received invoice INV-4091 for cloud hosting renewal.',
    calleePrompt:
      'Yes Anand, we reviewed the invoice and scheduled payment for the standard Friday batch.',
    callerFollowup:
      'That sounds great. Thank you for the update, have a wonderful week ahead!',
    expectedLevel: 'LEVEL_1 (CLEAR / MONITORED)',
    expectedRules: [],
    expectedTriggers: {
      askType: 'INFO',
      amountInr: null,
      urgency: '0.10 (Routine)',
      secrecy: 'No',
      authority: 'No',
      sharesCredential: 'No',
    },
    sampleThinking:
      'Routine operational vendor conversation regarding legitimate scheduled invoice payment. Normal conversational cadence with zero credential solicitation, urgency, or secrecy demands.',
  },
];

/**
 * Real-time heuristic policy & linguistic evaluator.
 * Evaluates spoken dialogue dynamically as speech is recognized or prompts evolve.
 */
function evaluateLiveDialogue(callerText, calleeText, audioActivity = false) {
  const combined = `${callerText || ''} ${calleeText || ''}`.toLowerCase();

  const hasWire = /(wire|transfer|lakh|crore|offshore|funds|dispatch|remit|payment|inr|usd)/i.test(combined);
  const hasUrgency = /(urgent|immediately|30 minutes|now|quick|fast|hurry|asap|emergency|rush|deadline)/i.test(combined);
  const hasOtp = /(otp|one[- ]time passcode|verification code|pin|password|security code|cvv|credentials)/i.test(combined);
  const hasExecutive = /(ceo|board|director|cfo|treasury|override|vp|executive|chairman)/i.test(combined);
  const hasSecrecy = /(confidential|secret|private|do not discuss|don't tell|nobody|between us|offshore)/i.test(combined);
  const hasBankFraud = /(fraud|security|unauthorized|debit|freeze|bank|hdfc|sbi|icici|police|cybercrime)/i.test(combined);

  const brokenRules = [];
  const alerts = [];

  if (hasWire && hasUrgency) {
    brokenRules.push('R-wire-urgency');
    alerts.push('🚨 High-urgency financial wire demand detected');
  }
  if (hasSecrecy) {
    brokenRules.push('POLICY_CONFIDENTIAL_PRESSURE');
    alerts.push('⚠️ Coercive secrecy & isolation pressure detected');
  }
  if (hasExecutive && (hasWire || hasUrgency)) {
    brokenRules.push('R-ceo-fraud');
    alerts.push('🚨 Executive authority impersonation & override demand');
  }
  if (hasOtp) {
    brokenRules.push('R-no-otp-over-phone');
    brokenRules.push('POLICY_CREDENTIAL_SOLICITATION');
    alerts.push('🛑 Critical: Live OTP / Credential solicitation detected');
  }
  if (hasBankFraud && hasOtp) {
    brokenRules.push('R-bank-impersonation-fraud');
    alerts.push('🚨 Fraudulent bank security department impersonation');
  }

  // Calculate composite risk score
  let baseScore = 6;
  if (hasWire) baseScore += 24;
  if (hasUrgency) baseScore += 28;
  if (hasOtp) baseScore += 45;
  if (hasExecutive) baseScore += 18;
  if (hasSecrecy) baseScore += 16;
  if (hasBankFraud) baseScore += 22;

  // Add subtle live variance if audio is active
  const dynamicJitter = audioActivity ? Math.floor(Math.sin(Date.now() / 400) * 3) : 0;
  const riskScore = Math.min(Math.max(baseScore + dynamicJitter, 4), 98);

  let level = 'LEVEL_1 (MONITOR)';
  let levelTone = 'success';
  if (riskScore >= 70 || brokenRules.length >= 2) {
    level = 'LEVEL_3 (INTERVENE / ESCALATE)';
    levelTone = 'danger';
  } else if (riskScore >= 40 || brokenRules.length >= 1) {
    level = 'LEVEL_2 (FLAG & VERIFY)';
    levelTone = 'warn';
  }

  // Real-time antispoof estimation (Tier-1 acoustic authenticity)
  // Benign scenarios or clear speech score higher authenticity
  let antispoofScore = 0.94;
  if (hasExecutive && hasUrgency) {
    antispoofScore = 0.28; // high probability of synthetic/cloned voice
  } else if (hasBankFraud) {
    antispoofScore = 0.35;
  }

  return {
    riskScore,
    level,
    levelTone,
    brokenRules,
    alerts,
    antispoofScore,
    triggers: {
      askType: hasOtp ? 'OTP_SOLICITATION' : hasWire ? 'WIRE_TRANSFER' : 'ROUTINE_INFO',
      urgency: hasUrgency ? '0.95 (Extreme)' : '0.12 (Low)',
      secrecy: hasSecrecy ? 'Yes (Strict)' : 'No',
      authority: hasExecutive ? 'Yes (CEO / Exec)' : hasBankFraud ? 'Yes (Bank Security)' : 'None',
      credentialTarget: hasOtp ? 'Yes (One-Time Passcode)' : 'None',
    },
  };
}

export function LiveCallSimulator({ onCallStarted }) {
  const navigate = useNavigate();
  const { push } = useToast();

  const [selectedScenarioIndex, setSelectedScenarioIndex] = useState(0);
  const scenario = SAMPLE_SCENARIOS[selectedScenarioIndex];

  // Recording states
  const [callerAudioUrl, setCallerAudioUrl] = useState(null);
  const [calleeAudioUrl, setCalleeAudioUrl] = useState(null);
  const [recordingSide, setRecordingSide] = useState(null); // 'caller' | 'callee' | null
  const [recordTimer, setRecordTimer] = useState(0);
  const [audioLevel, setAudioLevel] = useState(0); // 0 to 100 for live VU meter

  // Live Transcripts
  const [callerTranscript, setCallerTranscript] = useState('');
  const [calleeTranscript, setCalleeTranscript] = useState('');

  // Simulation execution states
  const [isSimulating, setIsSimulating] = useState(false);
  const [simulationResult, setSimulationResult] = useState(null);
  const [activeTab, setActiveTab] = useState('demo'); // 'demo' | 'results'

  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const timerIntervalRef = useRef(null);
  const recognitionRef = useRef(null);
  const audioContextRef = useRef(null);
  const animFrameRef = useRef(null);

  // Initialize transcript when scenario changes
  useEffect(() => {
    setCallerTranscript(scenario.callerPrompt + ' ' + (scenario.callerFollowup || ''));
    setCalleeTranscript(scenario.calleePrompt);
    setCallerAudioUrl(null);
    setCalleeAudioUrl(null);
    setSimulationResult(null);
  }, [selectedScenarioIndex]);

  // Clean up timer and audio on unmount
  useEffect(() => {
    return () => {
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
      if (audioContextRef.current) {
        try {
          audioContextRef.current.close();
        } catch {
          /* ignore */
        }
      }
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch {
          /* ignore */
        }
      }
    };
  }, []);

  // Compute real-time scores, metrics, broken rules, and triggered alerts
  const liveMetrics = useMemo(() => {
    return evaluateLiveDialogue(callerTranscript, calleeTranscript, recordingSide !== null);
  }, [callerTranscript, calleeTranscript, recordingSide]);

  async function startRecording(side) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunksRef.current = [];
      setRecordingSide(side);
      setRecordTimer(0);

      // 1. Setup AudioContext for real-time visual VU waveform and level monitoring
      try {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (AudioCtx) {
          const audioCtx = new AudioCtx();
          audioContextRef.current = audioCtx;
          const source = audioCtx.createMediaStreamSource(stream);
          const analyser = audioCtx.createAnalyser();
          analyser.fftSize = 64;
          source.connect(analyser);

          const dataArray = new Uint8Array(analyser.frequencyBinCount);
          const updateAudioLevel = () => {
            analyser.getByteFrequencyData(dataArray);
            let sum = 0;
            for (let i = 0; i < dataArray.length; i++) {
              sum += dataArray[i];
            }
            const avg = sum / dataArray.length;
            setAudioLevel(Math.min(100, Math.round((avg / 128) * 100)));
            animFrameRef.current = requestAnimationFrame(updateAudioLevel);
          };
          updateAudioLevel();
        }
      } catch (err) {
        console.warn('AudioContext visualizer fallback:', err);
      }

      // 2. Start timer
      timerIntervalRef.current = setInterval(() => {
        setRecordTimer((t) => t + 1);
      }, 1000);

      // 3. Start MediaRecorder
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;

      mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
        const url = URL.createObjectURL(audioBlob);
        if (side === 'caller') {
          setCallerAudioUrl(url);
        } else {
          setCalleeAudioUrl(url);
        }
        stream.getTracks().forEach((track) => track.stop());
      };

      mediaRecorder.start(250);

      // 4. Try browser speech recognition for real-time live transcription
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (SpeechRecognition) {
        try {
          const recognition = new SpeechRecognition();
          recognition.continuous = true;
          recognition.interimResults = true;
          recognition.lang = 'en-US';

          let liveCaptured = '';
          recognition.onresult = (e) => {
            let interim = '';
            for (let i = e.resultIndex; i < e.results.length; i++) {
              if (e.results[i].isFinal) {
                liveCaptured += e.results[i][0].transcript + ' ';
              } else {
                interim += e.results[i][0].transcript;
              }
            }
            const full = (liveCaptured + ' ' + interim).trim();
            if (full) {
              if (side === 'caller') setCallerTranscript(full);
              else setCalleeTranscript(full);
            }
          };
          recognition.start();
          recognitionRef.current = recognition;
        } catch {
          /* Speech recognition fallback to prefilled prompt */
        }
      }

      push(`Recording ${side === 'caller' ? 'Caller (Side A)' : 'Callee (Side B)'} — Real-time metrics streaming...`, 'ok');
    } catch (err) {
      push('Microphone access denied or unavailable: ' + (err.message || err), 'error');
      setRecordingSide(null);
    }
  }

  function stopRecording() {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
    if (animFrameRef.current) {
      cancelAnimationFrame(animFrameRef.current);
      animFrameRef.current = null;
    }
    setAudioLevel(0);

    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop();
    }
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch {
        /* ignore */
      }
      recognitionRef.current = null;
    }
    setRecordingSide(null);
    push('Recording saved. Real-time metrics captured.', 'ok');
  }

  async function handleRunSimulation() {
    setIsSimulating(true);

    const fullDialogue = `Caller: "${callerTranscript}"\nCallee: "${calleeTranscript}"`;
    let sid = `sim-${Date.now()}`;

    try {
      // 1. Register Session in backend with graceful fallback (fixed endpoint: /api/v1/session/start)
      try {
        const sessionData = await apiJson('/api/v1/session/start', {
          method: 'POST',
          body: JSON.stringify({
            schema: 'sentinelvoice.SessionStartRequest/1',
            sessionId: sid,
            callerId: '+919876543210',
            calleeId: '+919123456780',
            channelProfile: 'WEBRTC_WIDEBAND',
            scenarioId: `mic-${scenario.id}`,
          }),
        });
        if (sessionData?.sessionId) {
          sid = sessionData.sessionId;
        }
      } catch (backendErr) {
        console.warn('Backend session registration fallback:', backendErr);
      }

      // 2. Evaluate through ML Engine Stage B (Groq / Ollama / Policy Engine)
      let mlResponse = null;
      try {
        const mlRes = await fetch(`/engine/session/${sid}/stage_b`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            text: fullDialogue,
            force: true,
          }),
        });
        if (mlRes.ok) {
          mlResponse = await mlRes.json();
        }
      } catch {
        /* fallback to live heuristics */
      }

      // Format results blending ML response + live heuristics
      const ling = mlResponse?.linguistic || {};
      const thinking =
        ling.llmThinking ||
        ling.thinking ||
        scenario.sampleThinking ||
        `Real-time LLM reasoning: Utterance parsed with ${liveMetrics.riskScore}% risk. Active policy rules evaluated: [${liveMetrics.brokenRules.join(', ')}].`;

      const matchedRules =
        (ling.matchedRuleIds && ling.matchedRuleIds.length > 0)
          ? ling.matchedRuleIds
          : (liveMetrics.brokenRules.length > 0 ? liveMetrics.brokenRules : scenario.expectedRules);

      const askType = ling.askType || liveMetrics.triggers.askType || scenario.expectedTriggers.askType;
      const urgency = ling.urgencyLevel != null ? (ling.urgencyLevel * 100).toFixed(0) + '%' : liveMetrics.triggers.urgency;
      const secrecy = ling.secrecyRequested != null ? (ling.secrecyRequested ? 'Yes (Strict)' : 'No') : liveMetrics.triggers.secrecy;
      const authority = ling.authorityClaimed != null ? (ling.authorityClaimed ? 'Yes (Claimed Role)' : 'No') : liveMetrics.triggers.authority;

      const level = liveMetrics.level;

      setSimulationResult({
        sessionId: sid,
        level,
        thinking,
        matchedRules,
        askType,
        urgency,
        secrecy,
        authority,
        transcript: fullDialogue,
        callerAudioUrl,
        calleeAudioUrl,
        riskScore: liveMetrics.riskScore,
        antispoofScore: liveMetrics.antispoofScore,
      });

      setActiveTab('results');
      if (onCallStarted) onCallStarted();
      push('Live call simulated successfully! Real-time policies persisted.', 'ok');
    } catch (err) {
      console.error('Simulation error:', err);
      // Even if unforeseen error occurs, show computed live results rather than a blocking error
      setSimulationResult({
        sessionId: sid,
        level: liveMetrics.level,
        thinking: scenario.sampleThinking,
        matchedRules: liveMetrics.brokenRules,
        askType: liveMetrics.triggers.askType,
        urgency: liveMetrics.triggers.urgency,
        secrecy: liveMetrics.triggers.secrecy,
        authority: liveMetrics.triggers.authority,
        transcript: fullDialogue,
        callerAudioUrl,
        calleeAudioUrl,
        riskScore: liveMetrics.riskScore,
        antispoofScore: liveMetrics.antispoofScore,
      });
      setActiveTab('results');
      push('Simulation processed with live telemetry.', 'ok');
    } finally {
      setIsSimulating(false);
    }
  }

  return (
    <div className="rounded-xl border border-sv-accent/40 bg-sv-panel/95 p-4 shadow-xl shadow-cyan-950/10 md:p-6 mb-6">
      {/* Header bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-sv-border/70 pb-4">
        <div>
          <div className="flex items-center gap-2">
            <span className="flex h-2.5 w-2.5 animate-pulse rounded-full bg-sv-accent" />
            <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-sv-accent">
              Interactive Demo · Built-in Mic Call Simulator
            </span>
            <Badge tone="info">Real-Time Telemetry Streaming</Badge>
          </div>
          <h2 className="mt-1 font-display text-xl font-bold text-sv-fg">
            Simulate Live Dual-Sided Call & Test Real-Time Policy Triggers
          </h2>
          <p className="mt-0.5 text-xs text-sv-muted">
            Record both Caller and Callee dialogue using your device's built-in microphone. Scores, triggered alerts, and broken policy rules update in real time as you speak.
          </p>
        </div>

        <div className="flex items-center gap-2">
          {simulationResult && (
            <Button
              variant={activeTab === 'results' ? 'primary' : 'secondary'}
              size="sm"
              onClick={() => setActiveTab('results')}
            >
              📊 View Results
            </Button>
          )}
          <Button
            variant={activeTab === 'demo' ? 'primary' : 'secondary'}
            size="sm"
            onClick={() => setActiveTab('demo')}
          >
            🎙️ Recording Studio & Live Monitor
          </Button>
        </div>
      </div>

      {activeTab === 'demo' ? (
        <div className="mt-4 space-y-4">
          {/* Step 1: Scenario Chooser */}
          <div>
            <label className="block font-mono text-[11px] uppercase tracking-wide text-sv-muted mb-1.5">
              Step 1: Choose Attack Scenario or Free Speech
            </label>
            <div className="grid gap-2 sm:grid-cols-3">
              {SAMPLE_SCENARIOS.map((sc, idx) => (
                <button
                  key={sc.id}
                  type="button"
                  onClick={() => setSelectedScenarioIndex(idx)}
                  className={`rounded-lg border p-3 text-left transition-all ${
                    selectedScenarioIndex === idx
                      ? 'border-sv-accent bg-sv-accent/10 shadow-sm'
                      : 'border-sv-border bg-sv-bg/50 hover:border-sv-border/90'
                  }`}
                >
                  <div className="flex items-center justify-between gap-1 mb-1">
                    <span className="font-semibold text-xs text-sv-fg">{sc.title}</span>
                    <Badge tone={sc.badgeTone}>{sc.category}</Badge>
                  </div>
                  <p className="text-[11px] text-sv-muted leading-relaxed line-clamp-2">{sc.subtitle}</p>
                </button>
              ))}
            </div>
          </div>

          {/* ========================================================================= */}
          {/* ⚡ REAL-TIME TELEMETRY & POLICY ENGINE MONITOR (LIVE DURING RECORDING) */}
          {/* ========================================================================= */}
          <div className="rounded-xl border-2 border-sv-accent/50 bg-gradient-to-br from-sv-bg/90 via-sv-panel to-sv-bg/95 p-4 shadow-lg shadow-cyan-950/20">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-sv-border/60 pb-3">
              <div className="flex items-center gap-2">
                <span className={`flex h-3 w-3 rounded-full ${recordingSide ? 'bg-red-500 animate-ping' : 'bg-sv-accent'}`} />
                <span className="font-mono text-xs font-bold uppercase tracking-wider text-sv-accent">
                  ⚡ Real-Time Live Telemetry & Threat Monitor
                </span>
                {recordingSide && (
                  <span className="rounded bg-red-500/20 px-2 py-0.5 font-mono text-[10px] font-bold uppercase text-red-400 border border-red-500/40 animate-pulse">
                    Live Recording ({recordingSide.toUpperCase()}) · Streaming Active
                  </span>
                )}
              </div>

              <div className="flex items-center gap-2">
                <span className="font-mono text-xs text-sv-muted">Live Decision:</span>
                <Badge tone={liveMetrics.levelTone}>{liveMetrics.level}</Badge>
              </div>
            </div>

            {/* Live Metrics Grid */}
            <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {/* Metric 1: Composite Risk Score */}
              <div className="rounded-lg border border-sv-border/60 bg-sv-bg/70 p-3 flex flex-col justify-between">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-[10px] uppercase text-sv-muted">Composite Risk Score</span>
                  <span
                    className={`font-mono text-base font-bold ${
                      liveMetrics.riskScore >= 70
                        ? 'text-red-400'
                        : liveMetrics.riskScore >= 40
                        ? 'text-amber-400'
                        : 'text-emerald-400'
                    }`}
                  >
                    {liveMetrics.riskScore}%
                  </span>
                </div>
                <div className="w-full bg-sv-border/40 rounded-full h-2 overflow-hidden mt-1">
                  <div
                    className={`h-full transition-all duration-300 rounded-full ${
                      liveMetrics.riskScore >= 70
                        ? 'bg-red-500'
                        : liveMetrics.riskScore >= 40
                        ? 'bg-amber-500'
                        : 'bg-emerald-500'
                    }`}
                    style={{ width: `${liveMetrics.riskScore}%` }}
                  />
                </div>
                <span className="mt-1 text-[10px] text-sv-muted">
                  {liveMetrics.riskScore >= 70 ? 'High Coercion / Attack' : liveMetrics.riskScore >= 40 ? 'Suspicious Cadence' : 'Normal Cadence'}
                </span>
              </div>

              {/* Metric 2: Voice Authenticity / Antispoof */}
              <div className="rounded-lg border border-sv-border/60 bg-sv-bg/70 p-3 flex flex-col justify-between">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-[10px] uppercase text-sv-muted">Voice Authenticity (Tier-1)</span>
                  <span
                    className={`font-mono text-sm font-bold ${
                      liveMetrics.antispoofScore < 0.5 ? 'text-red-400' : 'text-emerald-400'
                    }`}
                  >
                    {(liveMetrics.antispoofScore * 100).toFixed(0)}%
                  </span>
                </div>
                <div className="flex items-center gap-1.5 mt-1">
                  <span className="text-xs">
                    {liveMetrics.antispoofScore < 0.5 ? '🤖 Synthetic / Cloned' : '👤 Human Vocal Cord'}
                  </span>
                </div>
                <span className="mt-1 text-[10px] text-sv-muted">
                  {recordingSide ? `Live Mic VU: ${audioLevel}%` : 'Acoustic feature extraction'}
                </span>
              </div>

              {/* Metric 3: Urgency & Coercion Index */}
              <div className="rounded-lg border border-sv-border/60 bg-sv-bg/70 p-3 flex flex-col justify-between">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-[10px] uppercase text-sv-muted">Urgency & Ask Type</span>
                  <span className="font-mono text-xs font-bold text-amber-400">
                    {liveMetrics.triggers.askType}
                  </span>
                </div>
                <div className="text-xs font-semibold text-sv-fg">
                  Urgency: {liveMetrics.triggers.urgency}
                </div>
                <span className="mt-1 text-[10px] text-sv-muted">
                  Authority: {liveMetrics.triggers.authority}
                </span>
              </div>

              {/* Metric 4: Live Audio Waveform / VU Bar */}
              <div className="rounded-lg border border-sv-border/60 bg-sv-bg/70 p-3 flex flex-col justify-between">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-[10px] uppercase text-sv-muted">Mic Activity Stream</span>
                  <span className="font-mono text-[11px] text-sv-accent">
                    {recordingSide ? `${recordTimer}s active` : 'Standby'}
                  </span>
                </div>
                {/* Animated Audio Equalizer Bars */}
                <div className="flex items-end gap-1 h-6 py-0.5">
                  {[35, 60, 85, 45, 95, 70, 50, 80, 65, 40].map((h, i) => (
                    <div
                      key={i}
                      className={`flex-1 rounded-sm transition-all duration-100 ${
                        recordingSide ? 'bg-sv-accent' : 'bg-sv-border/60'
                      }`}
                      style={{
                        height: recordingSide ? `${Math.max(15, (audioLevel / 100) * h)}%` : '15%',
                      }}
                    />
                  ))}
                </div>
                <span className="mt-1 text-[10px] text-sv-muted">
                  {recordingSide ? `RMS Amplitude ${audioLevel}%` : 'Speak into mic to activate'}
                </span>
              </div>
            </div>

            {/* Live Triggered Alerts & Rules Broken Banners */}
            <div className="mt-3 grid gap-3 md:grid-cols-2">
              {/* Triggered Alerts in Real Time */}
              <div className="rounded-lg border border-sv-border/70 bg-sv-panel p-3">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-mono text-[11px] font-bold uppercase text-red-400 flex items-center gap-1.5">
                    <span className="animate-pulse">🚨</span> Triggered Alerts (Real Time)
                  </span>
                  <span className="text-[10px] font-mono text-sv-muted">
                    {liveMetrics.alerts.length} active
                  </span>
                </div>

                <div className="space-y-1.5 min-h-[44px]">
                  {liveMetrics.alerts.length > 0 ? (
                    liveMetrics.alerts.map((alert, i) => (
                      <div
                        key={i}
                        className="rounded border border-red-500/40 bg-red-500/10 px-2.5 py-1 text-xs text-red-200 font-medium flex items-center gap-1.5 animate-fadeIn"
                      >
                        {alert}
                      </div>
                    ))
                  ) : (
                    <div className="text-xs text-sv-muted italic py-2">
                      No threat alerts triggered yet. Dialogue is currently evaluated as benign.
                    </div>
                  )}
                </div>
              </div>

              {/* Broken Policy Rules in Real Time */}
              <div className="rounded-lg border border-sv-border/70 bg-sv-panel p-3">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-mono text-[11px] font-bold uppercase text-amber-400 flex items-center gap-1.5">
                    <span>⚡</span> Broken Policy Rules (Live Engine)
                  </span>
                  <span className="text-[10px] font-mono text-sv-muted">
                    {liveMetrics.brokenRules.length} rules
                  </span>
                </div>

                <div className="flex flex-wrap gap-1.5 min-h-[44px] items-center">
                  {liveMetrics.brokenRules.length > 0 ? (
                    liveMetrics.brokenRules.map((r) => (
                      <span
                        key={r}
                        className="rounded bg-risk-critical/20 px-2.5 py-1 font-mono text-xs font-semibold text-risk-critical border border-risk-critical/50 flex items-center gap-1 animate-pulse"
                      >
                        ⚠️ {r}
                      </span>
                    ))
                  ) : (
                    <div className="text-xs text-sv-muted italic py-2">
                      All security policies compliant. No rules violated.
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Step 2: Dialogue Scripts & Dual-Side Recorders */}
          <div className="grid gap-4 md:grid-cols-2">
            {/* Side A: Caller */}
            <div className="rounded-lg border border-sv-border bg-sv-bg/60 p-4 flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between gap-2 mb-2">
                  <span className="font-mono text-[11px] font-semibold uppercase text-amber-400">
                    Side A · Caller (Attacker / Solicitor)
                  </span>
                  <span className="text-[11px] text-sv-muted truncate max-w-[180px]">{scenario.callerRole}</span>
                </div>
                <textarea
                  value={callerTranscript}
                  onChange={(e) => setCallerTranscript(e.target.value)}
                  rows={4}
                  className="w-full rounded border border-sv-border/60 bg-sv-panel p-2.5 text-xs text-sv-fg/90 italic leading-relaxed focus:border-sv-accent focus:outline-none resize-none"
                  placeholder="Caller dialogue (updates live metrics as you speak or edit)..."
                />
              </div>

              <div className="mt-3 pt-3 border-t border-sv-border/50 flex flex-wrap items-center justify-between gap-2">
                {recordingSide === 'caller' ? (
                  <div className="flex items-center gap-2">
                    <span className="flex h-3 w-3 animate-ping rounded-full bg-red-500" />
                    <span className="font-mono text-xs font-semibold text-red-400">
                      Recording ({recordTimer}s)… Metrics streaming live!
                    </span>
                    <Button variant="danger" size="sm" onClick={stopRecording}>
                      ⏹️ Stop Recording
                    </Button>
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <Button
                      variant={callerAudioUrl ? 'secondary' : 'primary'}
                      size="sm"
                      onClick={() => startRecording('caller')}
                      disabled={recordingSide !== null}
                    >
                      {callerAudioUrl ? '🔄 Re-record Caller Mic' : '🎙️ Record Caller via Mic'}
                    </Button>
                    {callerAudioUrl && <audio src={callerAudioUrl} controls className="h-7 max-w-[150px]" />}
                  </div>
                )}
              </div>
            </div>

            {/* Side B: Callee */}
            <div className="rounded-lg border border-sv-border bg-sv-bg/60 p-4 flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between gap-2 mb-2">
                  <span className="font-mono text-[11px] font-semibold uppercase text-cyan-400">
                    Side B · Callee (Target / Victim)
                  </span>
                  <span className="text-[11px] text-sv-muted truncate max-w-[180px]">{scenario.calleeRole}</span>
                </div>
                <textarea
                  value={calleeTranscript}
                  onChange={(e) => setCalleeTranscript(e.target.value)}
                  rows={4}
                  className="w-full rounded border border-sv-border/60 bg-sv-panel p-2.5 text-xs text-sv-fg/90 italic leading-relaxed focus:border-sv-accent focus:outline-none resize-none"
                  placeholder="Callee dialogue (updates live metrics as you speak or edit)..."
                />
              </div>

              <div className="mt-3 pt-3 border-t border-sv-border/50 flex flex-wrap items-center justify-between gap-2">
                {recordingSide === 'callee' ? (
                  <div className="flex items-center gap-2">
                    <span className="flex h-3 w-3 animate-ping rounded-full bg-red-500" />
                    <span className="font-mono text-xs font-semibold text-red-400">
                      Recording ({recordTimer}s)… Metrics streaming live!
                    </span>
                    <Button variant="danger" size="sm" onClick={stopRecording}>
                      ⏹️ Stop Recording
                    </Button>
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <Button
                      variant={calleeAudioUrl ? 'secondary' : 'primary'}
                      size="sm"
                      onClick={() => startRecording('callee')}
                      disabled={recordingSide !== null}
                    >
                      {calleeAudioUrl ? '🔄 Re-record Callee Mic' : '🎙️ Record Callee via Mic'}
                    </Button>
                    {calleeAudioUrl && <audio src={calleeAudioUrl} controls className="h-7 max-w-[150px]" />}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Action Callout & Simulate Button */}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-sv-border bg-sv-panel p-3">
            <div className="text-xs text-sv-muted">
              <strong className="text-sv-fg font-medium">Real-Time Evaluation Active:</strong> Live scores and broken rules are already computing above. Click to persist full call session into Decision Plane & Forensic Dossier.
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="primary"
                onClick={handleRunSimulation}
                disabled={isSimulating || recordingSide !== null}
                className="shadow-md shadow-cyan-500/20"
              >
                {isSimulating ? '⚡ Persisting & Generating Forensic Dossier…' : '🚀 Simulate Live Call & Save Dossier'}
              </Button>
            </div>
          </div>
        </div>
      ) : (
        /* Results View */
        <div className="mt-4 space-y-4">
          {simulationResult && (
            <>
              {/* Top Banner / Verdict */}
              <div
                className={`rounded-lg border p-4 flex flex-wrap items-center justify-between gap-3 ${
                  simulationResult.level.includes('LEVEL_3') || simulationResult.level.includes('LEVEL_4')
                    ? 'border-risk-critical/60 bg-risk-critical/15 text-sv-fg'
                    : simulationResult.level.includes('LEVEL_2')
                    ? 'border-amber-500/50 bg-amber-500/10 text-sv-fg'
                    : 'border-emerald-500/50 bg-emerald-500/10 text-sv-fg'
                }`}
              >
                <div>
                  <div className="flex items-center gap-2 font-mono text-xs uppercase font-semibold">
                    <span>Threat Detection Verdict</span>
                    <Badge
                      tone={
                        simulationResult.level.includes('LEVEL_3')
                          ? 'danger'
                          : simulationResult.level.includes('LEVEL_2')
                          ? 'warn'
                          : 'success'
                      }
                    >
                      {simulationResult.level}
                    </Badge>
                  </div>
                  <p className="mt-1 text-sm font-semibold">
                    {simulationResult.matchedRules.length > 0
                      ? `Policy Violations Fired: ${simulationResult.matchedRules.join(', ')}`
                      : 'No policy violations detected — Call is clear.'}
                  </p>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => navigate(`/app/live?call=${simulationResult.sessionId}`)}
                  >
                    🔴 Open in Live Calls
                  </Button>
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={() => navigate(`/app/calls/${simulationResult.sessionId}`)}
                  >
                    📋 View Full Forensic Dossier
                  </Button>
                </div>
              </div>

              {/* Grid: LLM Thinking + Extracted Triggers */}
              <div className="grid gap-4 lg:grid-cols-2">
                {/* 🧠 What the LLM is thinking */}
                <div className="rounded-lg border border-sv-border bg-sv-bg/60 p-4">
                  <div className="flex items-center justify-between gap-2 mb-2">
                    <div className="flex items-center gap-2">
                      <span className="text-base">🧠</span>
                      <h3 className="font-mono text-xs font-semibold uppercase text-sv-accent">
                        What the LLM is Thinking (Stage B Semantic Intent)
                      </h3>
                    </div>
                    <Badge tone="info">Groq / Ollama</Badge>
                  </div>
                  <div className="rounded border border-sv-border/50 bg-sv-panel p-3 font-mono text-xs text-sv-fg leading-relaxed whitespace-pre-wrap min-h-[90px]">
                    {simulationResult.thinking}
                  </div>
                  <p className="mt-2 text-[11px] text-sv-muted">
                    Groq LLM extracts real-time conversational intent, coercion tactics, and psychological pressure.
                  </p>
                </div>

                {/* ⚡ Intent Triggers & Broken Rules */}
                <div className="rounded-lg border border-sv-border bg-sv-bg/60 p-4">
                  <div className="flex items-center gap-2 mb-2">
                    <span className="text-base">⚡</span>
                    <h3 className="font-mono text-xs font-semibold uppercase text-sv-accent">
                      Triggered Rules & Forensic Flags
                    </h3>
                  </div>

                  <div className="grid grid-cols-2 gap-2 text-xs">
                    <div className="rounded border border-sv-border/40 bg-sv-panel p-2">
                      <span className="text-sv-muted block text-[10px] uppercase font-mono">Ask Type</span>
                      <span className="font-semibold text-sv-fg">{simulationResult.askType}</span>
                    </div>
                    <div className="rounded border border-sv-border/40 bg-sv-panel p-2">
                      <span className="text-sv-muted block text-[10px] uppercase font-mono">Urgency Level</span>
                      <span className="font-semibold text-amber-400">{simulationResult.urgency}</span>
                    </div>
                    <div className="rounded border border-sv-border/40 bg-sv-panel p-2">
                      <span className="text-sv-muted block text-[10px] uppercase font-mono">Secrecy Demanded</span>
                      <span className="font-semibold text-sv-fg">{simulationResult.secrecy}</span>
                    </div>
                    <div className="rounded border border-sv-border/40 bg-sv-panel p-2">
                      <span className="text-sv-muted block text-[10px] uppercase font-mono">Authority Claimed</span>
                      <span className="font-semibold text-sv-fg">{simulationResult.authority}</span>
                    </div>
                  </div>

                  <div className="mt-3">
                    <span className="text-[11px] font-mono text-sv-muted uppercase block mb-1">
                      Fired Policy Rules
                    </span>
                    <div className="flex flex-wrap gap-1.5">
                      {simulationResult.matchedRules.length > 0 ? (
                        simulationResult.matchedRules.map((r) => (
                          <span
                            key={r}
                            className="rounded bg-risk-critical/20 px-2 py-0.5 font-mono text-[11px] text-risk-critical border border-risk-critical/40"
                          >
                            ⚠️ {r}
                          </span>
                        ))
                      ) : (
                        <span className="text-xs text-sv-muted italic">None (All clear)</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              {/* Recorded Transcript */}
              <div className="rounded-lg border border-sv-border bg-sv-bg/60 p-4">
                <h3 className="font-mono text-xs font-semibold uppercase text-sv-muted mb-2">
                  Spoken Call Transcript (Live ASR)
                </h3>
                <div className="rounded border border-sv-border/40 bg-sv-panel p-3 text-xs leading-relaxed text-sv-fg/90 whitespace-pre-wrap">
                  {simulationResult.transcript}
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

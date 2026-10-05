import React, { useState, useRef, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { Badge, Button } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

export function LandingPage() {
  const navigate = useNavigate();
  const { login, isAuthenticated } = useAuth();
  const { push } = useToast();

  const [copiedField, setCopiedField] = useState(null);
  const [loggingIn, setLoggingIn] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  // Video player state
  const videoRef = useRef(null);
  const fileInputRef = useRef(null);
  const [videoSrc, setVideoSrc] = useState('/demo.mp4');
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [isMuted, setIsMuted] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1);
  const [isEnlarged, setIsEnlarged] = useState(false); // Theater mode
  const [isFullscreenModal, setIsFullscreenModal] = useState(false); // Modal enlarge
  const [videoError, setVideoError] = useState(false);
  const [customUrlInput, setCustomUrlInput] = useState('');
  const [showUrlInput, setShowUrlInput] = useState(false);

  const demoCreds = {
    tenant: 'telco',
    email: 'admin@telco.com',
    password: 'asdfgh123456',
  };

  const copyToClipboard = (text, fieldName) => {
    navigator.clipboard.writeText(text);
    setCopiedField(fieldName);
    push(`Copied ${fieldName} to clipboard!`, 'ok');
    setTimeout(() => setCopiedField(null), 2500);
  };

  const copyAllCreds = () => {
    const credText = `Tenant: ${demoCreds.tenant}\nEmail: ${demoCreds.email}\nPassword: ${demoCreds.password}`;
    navigator.clipboard.writeText(credText);
    setCopiedField('all');
    push('All demo credentials copied to clipboard!', 'ok');
    setTimeout(() => setCopiedField(null), 2500);
  };

  const handleOneClickLogin = async () => {
    setLoggingIn(true);
    try {
      await login({
        tenantSlug: demoCreds.tenant,
        email: demoCreds.email,
        password: demoCreds.password,
      });
      push('Signed in successfully as Telco Admin!', 'ok');
      navigate('/app');
    } catch (err) {
      console.warn('One-click login fallback to login page:', err);
      navigate(`/login?tenant=${demoCreds.tenant}&email=${encodeURIComponent(demoCreds.email)}&password=${demoCreds.password}`);
    } finally {
      setLoggingIn(false);
    }
  };

  // Video control functions
  const togglePlay = () => {
    if (!videoRef.current) return;
    if (videoRef.current.paused) {
      videoRef.current.play().then(() => setIsPlaying(true)).catch((e) => {
        console.warn('Play interrupted or no media:', e);
        setVideoError(true);
      });
    } else {
      videoRef.current.pause();
      setIsPlaying(false);
    }
  };

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      setCurrentTime(videoRef.current.currentTime);
      setDuration(videoRef.current.duration || 0);
    }
  };

  const handleSeek = (e) => {
    const time = parseFloat(e.target.value);
    if (videoRef.current) {
      videoRef.current.currentTime = time;
      setCurrentTime(time);
    }
  };

  const handleVolumeChange = (e) => {
    const val = parseFloat(e.target.value);
    setVolume(val);
    if (videoRef.current) {
      videoRef.current.volume = val;
      videoRef.current.muted = val === 0;
      setIsMuted(val === 0);
    }
  };

  const toggleMute = () => {
    if (videoRef.current) {
      const nextMuted = !isMuted;
      videoRef.current.muted = nextMuted;
      setIsMuted(nextMuted);
    }
  };

  const handleSpeedChange = (speed) => {
    setPlaybackSpeed(speed);
    if (videoRef.current) {
      videoRef.current.playbackRate = speed;
    }
  };

  const togglePiP = async () => {
    if (!videoRef.current) return;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else {
        await videoRef.current.requestPictureInPicture();
      }
    } catch (err) {
      push('Picture-in-Picture not supported: ' + err.message, 'warn');
    }
  };

  const toggleFullscreen = () => {
    if (videoRef.current) {
      if (videoRef.current.requestFullscreen) {
        videoRef.current.requestFullscreen().catch(() => {
          setIsFullscreenModal(!isFullscreenModal);
        });
      } else {
        setIsFullscreenModal(!isFullscreenModal);
      }
    } else {
      setIsFullscreenModal(!isFullscreenModal);
    }
  };

  const handleFileUpload = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      const url = URL.createObjectURL(file);
      setVideoSrc(url);
      setVideoError(false);
      push(`Loaded local video: ${file.name}`, 'ok');
      setTimeout(() => {
        if (videoRef.current) {
          videoRef.current.play().then(() => setIsPlaying(true)).catch(() => {});
        }
      }, 300);
    }
  };

  const handleApplyCustomUrl = (e) => {
    e.preventDefault();
    if (customUrlInput.trim()) {
      setVideoSrc(customUrlInput.trim());
      setVideoError(false);
      setShowUrlInput(false);
      push('Custom video source loaded!', 'ok');
      setTimeout(() => {
        if (videoRef.current) {
          videoRef.current.play().then(() => setIsPlaying(true)).catch(() => {});
        }
      }, 300);
    }
  };

  const formatTime = (secs) => {
    if (isNaN(secs) || secs < 0) return '00:00';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  };

  // Close modal on Escape
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape' && isFullscreenModal) {
        setIsFullscreenModal(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isFullscreenModal]);

  return (
    <div className="h-full w-full overflow-y-auto bg-sv-bg text-sv-fg antialiased selection:bg-sv-accent/30 selection:text-sv-accent">
      {/* Background ambient decorative glow */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden opacity-30">
        <div className="absolute -top-40 left-1/2 -translate-x-1/2 h-[500px] w-[900px] rounded-full bg-cyan-500/10 blur-[130px]" />
        <div className="absolute top-[600px] -left-40 h-[400px] w-[500px] rounded-full bg-indigo-500/10 blur-[120px]" />
      </div>

      {/* Top Navigation Bar */}
      <nav className="sticky top-0 z-50 border-b border-sv-border/80 bg-sv-bg/90 backdrop-blur-md">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6 lg:px-8">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-cyan-500/10 border border-cyan-500/30 text-sv-accent shadow-sm">
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 2L3 7v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V7l-9-5z" />
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 11a2 2 0 100-4 2 2 0 000 4zm0 2c-2.67 0-8 1.34-8 4v1h16v-1c0-2.66-5.33-4-8-4z" />
              </svg>
            </div>
            <div>
              <span className="font-display text-lg font-bold tracking-tight text-sv-fg">
                Sentinel<span className="text-sv-accent">Voice</span>
              </span>
              <span className="ml-2 hidden rounded-full bg-sv-accent/15 px-2 py-0.5 font-mono text-[10px] font-semibold text-sv-accent border border-sv-accent/30 sm:inline">
                v2.4 Telephony Defense
              </span>
            </div>
          </div>

          {/* Quick Nav Links (Desktop) */}
          <div className="hidden lg:flex items-center gap-5 text-xs font-medium text-sv-muted">
            <a href="#video-front" className="hover:text-sv-fg transition-colors flex items-center gap-1 font-semibold text-sv-accent">
              <span>▶</span> Project Video
            </a>
            <a href="#credentials" className="hover:text-sv-fg transition-colors">Demo Credentials</a>
            <a href="#demo-mode" className="hover:text-sv-fg transition-colors">How Demo Works</a>
            <a href="#llm-architecture" className="hover:text-sv-fg transition-colors">Ollama vs Groq</a>
            <a href="#capabilities" className="hover:text-sv-fg transition-colors">Capabilities</a>
          </div>

          {/* Auth Action Buttons (Desktop & Tablet) */}
          <div className="hidden sm:flex items-center gap-2.5">
            {isAuthenticated ? (
              <Button variant="primary" size="sm" onClick={() => navigate('/app')}>
                Enter Console 🚀
              </Button>
            ) : (
              <>
                <Button variant="secondary" size="sm" onClick={() => navigate('/login')}>
                  Sign In
                </Button>
                <Button variant="primary" size="sm" onClick={() => navigate('/register')}>
                  Register
                </Button>
              </>
            )}
          </div>

          {/* Mobile Hamburger Toggle */}
          <div className="sm:hidden flex items-center gap-2">
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="rounded-lg border border-sv-border/80 bg-sv-panel p-2 text-sv-fg hover:text-sv-accent"
              aria-label="Toggle menu"
            >
              {mobileMenuOpen ? '✕' : '☰'}
            </button>
          </div>
        </div>

        {/* Mobile Dropdown Menu */}
        {mobileMenuOpen && (
          <div className="sm:hidden border-b border-sv-border bg-sv-panel/95 px-4 py-3 space-y-2 text-xs">
            <a
              href="#video-front"
              onClick={() => setMobileMenuOpen(false)}
              className="block py-1.5 text-sv-accent font-semibold"
            >
              🎥 Project Video Player (Front)
            </a>
            <a
              href="#credentials"
              onClick={() => setMobileMenuOpen(false)}
              className="block py-1.5 text-sv-fg font-medium hover:text-sv-accent"
            >
              🔑 Demo Login Credentials
            </a>
            <a
              href="#demo-mode"
              onClick={() => setMobileMenuOpen(false)}
              className="block py-1.5 text-sv-fg font-medium hover:text-sv-accent"
            >
              🎙️ How Demo Mode Works
            </a>
            <a
              href="#llm-architecture"
              onClick={() => setMobileMenuOpen(false)}
              className="block py-1.5 text-sv-fg font-medium hover:text-sv-accent"
            >
              🛡️ Ollama On-Premise Privacy
            </a>
            <div className="pt-2 border-t border-sv-border flex items-center gap-2">
              <Button variant="secondary" size="sm" className="w-1/2" onClick={() => navigate('/login')}>
                Sign In
              </Button>
              <Button variant="primary" size="sm" className="w-1/2" onClick={() => navigate('/register')}>
                Register
              </Button>
            </div>
          </div>
        )}
      </nav>

      {/* Main Content Container */}
      <main className="mx-auto max-w-7xl px-3 sm:px-6 lg:px-8 py-6 sm:py-8 space-y-12 sm:space-y-16">
        
        {/* ========================================================================= */}
        {/* HERO SECTION WITH SPLIT LEFT / RIGHT LAYOUT (VIDEO SHIFTED UP) */}
        {/* ========================================================================= */}
        <section className="relative pt-2 pb-2 md:pt-4">
          <div className="grid gap-6 lg:grid-cols-12 lg:items-center">
            
            {/* LEFT COLUMN: Problem statement, Headline, Subtitle, Actions, and Quick Highlights */}
            <div className={`transition-all duration-300 ${isEnlarged ? 'lg:col-span-12 text-center mb-2' : 'lg:col-span-5 text-left'}`}>
              <div className="inline-flex items-center gap-2 rounded-full border border-sv-accent/40 bg-sv-accent/10 px-3 py-1 text-xs text-sv-accent mb-3 sm:mb-4 shadow-sm">
                <span className="flex h-2 w-2 rounded-full bg-sv-accent animate-ping" />
                <span className="font-mono font-semibold uppercase tracking-wider text-[11px]">
                  Smart India Hackathon · Problem Statement 104
                </span>
              </div>

              <h1 className="font-display text-2xl font-extrabold tracking-tight sm:text-3xl md:text-4xl lg:text-[38px] text-sv-fg leading-tight">
                Real-Time Deepfake Detection & <br />
                <span className="bg-gradient-to-r from-cyan-400 via-teal-300 to-indigo-400 bg-clip-text text-transparent">
                  Coercive Voice Fraud Interception
                </span>
              </h1>

              <p className="mt-3 text-xs sm:text-sm text-sv-muted leading-relaxed">
                Enterprise voice defense engine analyzing live conversational audio for AI voice cloning, executive impersonation, urgent offshore wire transfers, and OTP credential harvesting before financial damage occurs.
              </p>

              {/* Quick Action Navigation Buttons */}
              <div className="mt-5 flex flex-wrap items-center gap-2.5 sm:gap-3">
                <Button
                  variant="primary"
                  size="md"
                  onClick={handleOneClickLogin}
                  disabled={loggingIn}
                  className="w-full sm:w-auto shadow-lg shadow-cyan-500/25 px-5 font-semibold text-xs sm:text-sm"
                >
                  {loggingIn ? 'Signing In as Telco…' : '⚡ One-Click Demo Sign In'}
                </Button>
                <Button
                  variant="secondary"
                  size="md"
                  onClick={() => navigate('/login')}
                  className="w-full sm:w-auto px-4 text-xs sm:text-sm"
                >
                  🔐 Console Sign In
                </Button>
                <Button
                  variant="ghost"
                  size="md"
                  onClick={() => navigate('/register')}
                  className="w-full sm:w-auto border border-sv-border/80 px-4 text-xs sm:text-sm hover:bg-sv-panel"
                >
                  🏢 Register Tenant
                </Button>
              </div>

              {/* Metrics Highlights (compact grid) */}
              <div className="mt-6 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-2">
                <div className="rounded-lg border border-sv-border/80 bg-sv-panel/80 p-2.5 backdrop-blur">
                  <div className="font-mono text-base font-bold text-sv-accent">&lt; 150ms</div>
                  <div className="text-[10px] text-sv-muted">Streaming Feature Latency</div>
                </div>
                <div className="rounded-lg border border-sv-border/80 bg-sv-panel/80 p-2.5 backdrop-blur">
                  <div className="font-mono text-base font-bold text-emerald-400">100% Privacy</div>
                  <div className="text-[10px] text-sv-muted">On-Premises Local Ollama</div>
                </div>
                <div className="rounded-lg border border-sv-border/80 bg-sv-panel/80 p-2.5 backdrop-blur">
                  <div className="font-mono text-base font-bold text-amber-400">Level 1 - 4</div>
                  <div className="text-[10px] text-sv-muted">Automated Escalation Ladder</div>
                </div>
                <div className="rounded-lg border border-sv-border/80 bg-sv-panel/80 p-2.5 backdrop-blur">
                  <div className="font-mono text-base font-bold text-cyan-400">Zero-Config</div>
                  <div className="text-[10px] text-sv-muted">Built-in Browser Mic Studio</div>
                </div>
              </div>
            </div>

            {/* RIGHT COLUMN: Video Player shifted up directly side-by-side with hero text */}
            <div id="video-front" className={`transition-all duration-300 ${isEnlarged ? 'lg:col-span-12' : 'lg:col-span-7'}`}>
              <div className="rounded-2xl border-2 border-sv-accent/60 bg-gradient-to-br from-sv-panel via-sv-panel/95 to-cyan-950/20 p-3 sm:p-5 shadow-2xl shadow-cyan-950/40 text-left">
              
              {/* Video Player Header & Controls Bar */}
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-sv-border/70 pb-3 mb-3">
                <div className="flex items-center gap-2">
                  <span className="flex h-3 w-3 rounded-full bg-sv-accent animate-ping" />
                  <span className="font-mono text-xs font-bold uppercase tracking-wider text-sv-accent">
                    Project Presentation & Demonstration Video
                  </span>
                  <Badge tone="info">Live Player</Badge>
                </div>

                {/* Quick Player Option Actions */}
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="video/*"
                    onChange={handleFileUpload}
                    className="hidden"
                  />
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => fileInputRef.current?.click()}
                    title="Select video file from your computer"
                    className="text-xs"
                  >
                    📁 Choose Local Video
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setShowUrlInput(!showUrlInput)}
                    className="border border-sv-border text-xs"
                  >
                    🔗 URL Source
                  </Button>
                  <Button
                    variant={isEnlarged ? 'primary' : 'secondary'}
                    size="sm"
                    onClick={() => setIsEnlarged(!isEnlarged)}
                    className="text-xs font-medium"
                    title="Toggle Enlarge Theater Mode"
                  >
                    {isEnlarged ? '⤢ Standard Size' : '⤡ Enlarge Theater'}
                  </Button>
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={toggleFullscreen}
                    className="text-xs shadow-sm font-semibold"
                    title="Open Fullscreen player"
                  >
                    🗖 Fullscreen
                  </Button>
                </div>
              </div>

              {/* Optional URL Input Popover */}
              {showUrlInput && (
                <form onSubmit={handleApplyCustomUrl} className="mb-3 flex gap-2 rounded-lg border border-sv-border bg-sv-bg p-2 text-xs">
                  <input
                    type="text"
                    value={customUrlInput}
                    onChange={(e) => setCustomUrlInput(e.target.value)}
                    placeholder="Paste direct video URL (e.g., https://.../video.mp4 or /demo.mp4)..."
                    className="flex-1 bg-transparent px-2 text-xs text-sv-fg placeholder-sv-muted/60 focus:outline-none"
                  />
                  <Button type="submit" size="sm" variant="primary">
                    Apply URL
                  </Button>
                  <Button type="button" size="sm" variant="ghost" onClick={() => setShowUrlInput(false)}>
                    Cancel
                  </Button>
                </form>
              )}

              {/* Main Video Frame */}
              <div className="relative aspect-video w-full overflow-hidden rounded-xl border border-sv-border/80 bg-black shadow-2xl group">
                <video
                  ref={videoRef}
                  src={videoSrc}
                  className="h-full w-full object-contain cursor-pointer"
                  onClick={togglePlay}
                  onTimeUpdate={handleTimeUpdate}
                  onLoadedMetadata={handleTimeUpdate}
                  onEnded={() => setIsPlaying(false)}
                  onError={() => setVideoError(true)}
                  playsInline
                />

                {/* Big Center Play/Pause Overlay Button */}
                {!isPlaying && (
                  <div
                    onClick={togglePlay}
                    className="absolute inset-0 flex flex-col items-center justify-center bg-black/40 backdrop-blur-[2px] transition-all cursor-pointer p-4 text-center"
                  >
                    <div className="flex h-16 w-16 sm:h-20 sm:w-20 items-center justify-center rounded-full bg-cyan-500/90 text-black shadow-2xl shadow-cyan-500/50 hover:scale-105 transition-transform">
                      <svg className="h-8 w-8 sm:h-10 sm:w-10 ml-1" viewBox="0 0 24 24" fill="currentColor">
                        <polygon points="5 3 19 12 5 21 5 3" />
                      </svg>
                    </div>
                    <span className="mt-3 font-display text-sm sm:text-base font-semibold text-white drop-shadow">
                      Click to Play Project Video
                    </span>
                    {videoError && (
                      <span className="mt-2 max-w-md rounded bg-red-500/30 px-3 py-1.5 text-[11px] text-red-200 border border-red-500/50">
                        Default video file not yet placed. Click <strong>"📁 Choose Local Video"</strong> above or paste video into <code>frontend/public/demo.mp4</code>.
                      </span>
                    )}
                  </div>
                )}

                {/* Custom Overlay Controls Bar (Bottom) */}
                <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/95 via-black/75 to-transparent p-2 sm:p-3 transition-opacity">
                  {/* Progress Seek Bar */}
                  <input
                    type="range"
                    min="0"
                    max={duration || 100}
                    value={currentTime}
                    onChange={handleSeek}
                    className="h-1.5 w-full cursor-pointer appearance-none rounded-lg bg-sv-border/60 accent-sv-accent hover:h-2 transition-all"
                  />

                  {/* Controls Row */}
                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-white">
                    {/* Left: Play/Pause, Time, Volume */}
                    <div className="flex items-center gap-3">
                      <button
                        onClick={togglePlay}
                        className="rounded p-1 text-sv-accent hover:text-white transition-colors"
                        title={isPlaying ? 'Pause' : 'Play'}
                      >
                        {isPlaying ? (
                          <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 24 24">
                            <rect x="6" y="4" width="4" height="16" />
                            <rect x="14" y="4" width="4" height="16" />
                          </svg>
                        ) : (
                          <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 24 24">
                            <polygon points="5 3 19 12 5 21 5 3" />
                          </svg>
                        )}
                      </button>

                      {/* Time display */}
                      <span className="font-mono text-[11px] text-sv-muted">
                        {formatTime(currentTime)} / {formatTime(duration)}
                      </span>

                      {/* Volume & Mute */}
                      <div className="hidden sm:flex items-center gap-1.5">
                        <button onClick={toggleMute} className="text-sv-muted hover:text-white" title={isMuted ? 'Unmute' : 'Mute'}>
                          {isMuted || volume === 0 ? '🔇' : '🔊'}
                        </button>
                        <input
                          type="range"
                          min="0"
                          max="1"
                          step="0.05"
                          value={isMuted ? 0 : volume}
                          onChange={handleVolumeChange}
                          className="h-1 w-16 appearance-none rounded bg-sv-border accent-sv-accent cursor-pointer"
                        />
                      </div>
                    </div>

                    {/* Right: Speed, PiP, Enlarge, Fullscreen */}
                    <div className="flex items-center gap-2">
                      {/* Speed selector */}
                      <div className="flex items-center rounded border border-sv-border/70 bg-black/60 px-1 py-0.5 text-[10px] font-mono">
                        {[0.75, 1, 1.25, 1.5, 2].map((s) => (
                          <button
                            key={s}
                            onClick={() => handleSpeedChange(s)}
                            className={`px-1 rounded ${playbackSpeed === s ? 'bg-sv-accent text-black font-bold' : 'text-sv-muted hover:text-white'}`}
                          >
                            {s}x
                          </button>
                        ))}
                      </div>

                      {/* Picture-in-Picture */}
                      <button
                        onClick={togglePiP}
                        className="hidden sm:block text-sv-muted hover:text-white transition-colors p-1"
                        title="Picture in Picture"
                      >
                        🗗
                      </button>

                      {/* Enlarge / Fullscreen */}
                      <button
                        onClick={toggleFullscreen}
                        className="text-sv-accent hover:text-white transition-colors p-1 font-bold"
                        title="Fullscreen view"
                      >
                        🗖
                      </button>
                    </div>
                  </div>
                </div>
              </div>

              {/* Video Instructions Footer */}
              <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2 text-[11px] text-sv-muted">
                <div>
                  💡 <strong>Video location:</strong> Paste your video into <code className="text-sv-accent">frontend/public/demo.mp4</code> or click <strong>"📁 Choose Local Video"</strong> above to play any file.
                </div>
                <div className="hidden sm:flex items-center gap-2">
                  <span className="font-mono text-sv-fg">Enlarge: [⤡ Theater Mode] or [🗖 Fullscreen]</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

        {/* ========================================================================= */}
        {/* FULLSCREEN MODAL ENLARGE VIEW (IF REQUESTED) */}
        {/* ========================================================================= */}
        {isFullscreenModal && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/95 p-4 backdrop-blur-md">
            <div className="relative w-full max-w-6xl aspect-video rounded-2xl border border-sv-accent/60 overflow-hidden shadow-2xl bg-black">
              <button
                onClick={() => setIsFullscreenModal(false)}
                className="absolute top-4 right-4 z-10 rounded-full bg-black/70 border border-sv-border px-3 py-1.5 text-xs text-white hover:bg-sv-accent hover:text-black font-mono transition-colors"
              >
                ✕ Close Enlarged View (Esc)
              </button>
              <video
                src={videoSrc}
                controls
                autoPlay
                className="h-full w-full object-contain"
              />
            </div>
          </div>
        )}

        {/* ========================================================================= */}
        {/* DEMO CREDENTIALS BOX (PROMINENT & INTERACTIVE) */}
        {/* ========================================================================= */}
        <section id="credentials" className="relative scroll-mt-20">
          <div className="rounded-2xl border-2 border-sv-accent/60 bg-gradient-to-br from-sv-panel via-sv-panel/95 to-cyan-950/20 p-5 sm:p-8 shadow-2xl shadow-cyan-950/30">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-sv-border/80 pb-5">
              <div>
                <div className="flex items-center gap-2">
                  <span className="flex h-3 w-3 rounded-full bg-sv-accent animate-ping" />
                  <span className="font-mono text-xs font-bold uppercase tracking-wider text-sv-accent">
                    Evaluation & Jury Access Credentials
                  </span>
                  <Badge tone="success">Pre-Seeded Workspace</Badge>
                </div>
                <h2 className="mt-1 font-display text-xl font-bold text-sv-fg sm:text-2xl">
                  Sign in with Preconfigured Telco Demo Account
                </h2>
                <p className="mt-1 text-xs sm:text-sm text-sv-muted">
                  Use these credentials to access pre-loaded call sessions, live policy rules, compliance audit records, and telephony controls.
                </p>
              </div>

              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={copyAllCreds}
                  className="font-mono text-xs"
                >
                  {copiedField === 'all' ? '✅ Copied All!' : '📋 Copy All'}
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  onClick={handleOneClickLogin}
                  disabled={loggingIn}
                  className="shadow-md shadow-cyan-500/20 font-semibold"
                >
                  {loggingIn ? 'Logging in…' : '⚡ One-Click Direct Sign In'}
                </Button>
              </div>
            </div>

            {/* Credential Cards Grid */}
            <div className="mt-6 grid gap-3 sm:grid-cols-3 sm:gap-4">
              {/* Tenant Slug */}
              <div className="group rounded-xl border border-sv-border/80 bg-sv-bg/80 p-4 transition-all hover:border-sv-accent/50">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
                    Username / Tenant Slug
                  </span>
                  <button
                    onClick={() => copyToClipboard(demoCreds.tenant, 'Tenant')}
                    className="text-xs text-sv-accent hover:underline font-mono"
                  >
                    {copiedField === 'Tenant' ? 'Copied!' : 'Copy'}
                  </button>
                </div>
                <div className="mt-2 font-mono text-base sm:text-lg font-bold text-sv-fg select-all">
                  {demoCreds.tenant}
                </div>
                <p className="mt-1 text-[11px] text-sv-muted">
                  Pre-configured telecommunications operator workspace
                </p>
              </div>

              {/* Admin Email */}
              <div className="group rounded-xl border border-sv-border/80 bg-sv-bg/80 p-4 transition-all hover:border-sv-accent/50">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
                    Admin Email Address
                  </span>
                  <button
                    onClick={() => copyToClipboard(demoCreds.email, 'Email')}
                    className="text-xs text-sv-accent hover:underline font-mono"
                  >
                    {copiedField === 'Email' ? 'Copied!' : 'Copy'}
                  </button>
                </div>
                <div className="mt-2 font-mono text-base sm:text-lg font-bold text-sv-fg select-all break-all">
                  {demoCreds.email}
                </div>
                <p className="mt-1 text-[11px] text-sv-muted">
                  Workspace Super-Admin with full policy & audit access
                </p>
              </div>

              {/* Password */}
              <div className="group rounded-xl border border-sv-border/80 bg-sv-bg/80 p-4 transition-all hover:border-sv-accent/50">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
                    Security Password
                  </span>
                  <button
                    onClick={() => copyToClipboard(demoCreds.password, 'Password')}
                    className="text-xs text-sv-accent hover:underline font-mono"
                  >
                    {copiedField === 'Password' ? 'Copied!' : 'Copy'}
                  </button>
                </div>
                <div className="mt-2 font-mono text-base sm:text-lg font-bold text-sv-fg select-all">
                  {demoCreds.password}
                </div>
                <p className="mt-1 text-[11px] text-sv-muted">
                  Development evaluation password
                </p>
              </div>
            </div>

            {/* Quick action bar */}
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-sv-border/60 bg-sv-elevated/40 p-3 text-xs text-sv-muted">
              <div>
                💡 <strong>Tip:</strong> You can also manually fill these at{' '}
                <Link to="/login" className="text-sv-accent underline hover:text-cyan-300">
                  /login
                </Link>{' '}
                or click the <strong>One-Click Direct Sign In</strong> button to jump straight in.
              </div>
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => navigate(`/login?tenant=${demoCreds.tenant}&email=${encodeURIComponent(demoCreds.email)}&password=${demoCreds.password}`)}
                >
                  Open Login with Prefilled Data →
                </Button>
              </div>
            </div>
          </div>
        </section>

        {/* ========================================================================= */}
        {/* HOW DEMO MODE WORKS */}
        {/* ========================================================================= */}
        <section id="demo-mode" className="scroll-mt-20">
          <div className="text-center max-w-3xl mx-auto mb-8 sm:mb-10">
            <span className="font-mono text-xs font-semibold uppercase tracking-wider text-sv-accent">
              Zero-Hardware Softphone Testing
            </span>
            <h2 className="mt-2 font-display text-2xl sm:text-3xl font-bold text-sv-fg">
              How the Interactive Demo Mode Works
            </h2>
            <p className="mt-2 text-xs sm:text-sm text-sv-muted">
              Designed specifically so evaluators, judges, and developers can test full voice fraud detection and policy enforcement without needing complex SIP softphones (Zoiper/MicroSIP) or PBX hardware.
            </p>
          </div>

          <div className="grid gap-4 sm:gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {/* Step 1 */}
            <div className="rounded-xl border border-sv-border bg-sv-panel p-5 flex flex-col justify-between">
              <div>
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-cyan-500/10 text-sv-accent font-mono font-bold border border-cyan-500/30 mb-4">
                  01
                </div>
                <h3 className="font-display text-base font-bold text-sv-fg mb-1.5">
                  Dual-Sided Mic Recording
                </h3>
                <p className="text-xs text-sv-muted leading-relaxed">
                  Record both <strong>Side A (Caller / Attacker)</strong> and <strong>Side B (Callee / Target)</strong> directly using your browser's microphone. You can test preset attack prompts or speak freely.
                </p>
              </div>
              <div className="mt-4 pt-3 border-t border-sv-border/50 text-[11px] font-mono text-cyan-400">
                🎙️ Web Speech API + Web Audio
              </div>
            </div>

            {/* Step 2 */}
            <div className="rounded-xl border border-sv-border bg-sv-panel p-5 flex flex-col justify-between">
              <div>
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-teal-500/10 text-teal-400 font-mono font-bold border border-teal-500/30 mb-4">
                  02
                </div>
                <h3 className="font-display text-base font-bold text-sv-fg mb-1.5">
                  Live Streaming Telemetry
                </h3>
                <p className="text-xs text-sv-muted leading-relaxed">
                  As you speak, live metrics update in <strong>real time</strong>—VU audio levels, Tier-1 synthetic voice antispoof scores, spectral energy, and composite risk percentages without waiting to submit.
                </p>
              </div>
              <div className="mt-4 pt-3 border-t border-sv-border/50 text-[11px] font-mono text-teal-400">
                📊 Real-Time RMS & Biometrics
              </div>
            </div>

            {/* Step 3 */}
            <div className="rounded-xl border border-sv-border bg-sv-panel p-5 flex flex-col justify-between">
              <div>
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-amber-500/10 text-amber-400 font-mono font-bold border border-amber-500/30 mb-4">
                  03
                </div>
                <h3 className="font-display text-base font-bold text-sv-fg mb-1.5">
                  Real-Time Rules & Alerts
                </h3>
                <p className="text-xs text-sv-muted leading-relaxed">
                  Watch policy violations fire as you utter coercion keywords. Instant detection of high-urgency wires (<code className="text-amber-400">R-wire-urgency</code>), OTP theft (<code className="text-amber-400">R-no-otp</code>), and executive pressure.
                </p>
              </div>
              <div className="mt-4 pt-3 border-t border-sv-border/50 text-[11px] font-mono text-amber-400">
                ⚡ Instant Alert Badges
              </div>
            </div>

            {/* Step 4 */}
            <div className="rounded-xl border border-sv-border bg-sv-panel p-5 flex flex-col justify-between">
              <div>
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-purple-500/10 text-purple-400 font-mono font-bold border border-purple-500/30 mb-4">
                  04
                </div>
                <h3 className="font-display text-base font-bold text-sv-fg mb-1.5">
                  Automated Escalation Ladder
                </h3>
                <p className="text-xs text-sv-muted leading-relaxed">
                  Experience SentinelVoice's response engine: silent logging (Level 1) $\rightarrow$ supervisor warnings (Level 2) $\rightarrow$ agent whisper injection & call termination (Level 3/4) with complete cryptographic dossier.
                </p>
              </div>
              <div className="mt-4 pt-3 border-t border-sv-border/50 text-[11px] font-mono text-purple-400">
                🛡️ Level 1–4 Decision Ladder
              </div>
            </div>
          </div>
        </section>

        {/* ========================================================================= */}
        {/* AI ARCHITECTURE: CLOUD EVALUATION VS ON-PREMISE PRODUCTION PRIVACY */}
        {/* ========================================================================= */}
        <section id="llm-architecture" className="scroll-mt-20">
          <div className="rounded-2xl border border-sv-border bg-sv-panel/90 p-5 sm:p-8 backdrop-blur">
            <div className="text-center max-w-3xl mx-auto mb-6 sm:mb-8">
              <span className="font-mono text-xs font-semibold uppercase tracking-wider text-sv-accent">
                Security & Data Privacy Architecture
              </span>
              <h2 className="mt-2 font-display text-2xl sm:text-3xl font-bold text-sv-fg">
                Dual AI Engine: Cloud Evaluation vs On-Premise Production Privacy
              </h2>
              <p className="mt-2 text-xs sm:text-sm text-sv-muted">
                Transparent disclosure of our current evaluation architecture and enterprise production deployment design.
              </p>
            </div>

            <div className="grid gap-6 lg:grid-cols-2">
              {/* Cloud LLM (Current Evaluation Demo) */}
              <div className="rounded-xl border border-sv-border bg-sv-bg/60 p-5 flex flex-col justify-between">
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <span className="font-mono text-xs font-bold uppercase text-amber-400 flex items-center gap-1.5">
                      <span>☁️</span> Evaluation & Demo Setup (Current)
                    </span>
                    <Badge tone="warn">External Cloud LLM API</Badge>
                  </div>
                  <h3 className="font-display text-lg font-bold text-sv-fg mb-2">
                    Groq Cloud API Acceleration
                  </h3>
                  <p className="text-xs text-sv-muted leading-relaxed mb-4">
                    For public evaluation, live hackathon demonstrations, and continuous testing without dedicated GPU server infrastructure, SentinelVoice connects to:
                  </p>

                  <div className="rounded-lg border border-sv-border/60 bg-sv-panel p-3 font-mono text-xs space-y-1.5 mb-4 text-sv-fg/90">
                    <div><span className="text-sv-muted">API Endpoint:</span> https://api.groq.com/openai</div>
                    <div><span className="text-sv-muted">Model ID:</span> openai/gpt-oss-120b</div>
                    <div><span className="text-sv-muted">Role:</span> Stage B Semantic Coercion & Intent Extraction</div>
                    <div><span className="text-sv-muted">Speed:</span> Ultra-high token generation (&gt;300 tokens/sec)</div>
                  </div>

                  <p className="text-xs text-sv-muted leading-relaxed">
                    This setup allows any evaluator or device to test complex conversational intelligence with zero local hardware load.
                  </p>
                </div>

                <div className="mt-4 pt-3 border-t border-sv-border/50 text-[11px] text-amber-300/80">
                  ⚡ Optimized for instant hackathon jury response & live demos
                </div>
              </div>

              {/* On-Premises Ollama (Real Production Architecture) */}
              <div className="rounded-xl border-2 border-emerald-500/40 bg-gradient-to-br from-emerald-950/20 via-sv-bg/80 to-sv-panel p-5 flex flex-col justify-between shadow-lg shadow-emerald-950/20">
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <span className="font-mono text-xs font-bold uppercase text-emerald-400 flex items-center gap-1.5">
                      <span>🛡️</span> Production Enterprise Architecture
                    </span>
                    <Badge tone="success">100% On-Premise Local Ollama</Badge>
                  </div>
                  <h3 className="font-display text-lg font-bold text-sv-fg mb-2">
                    Air-Gapped Local Ollama Privacy
                  </h3>
                  <p className="text-xs text-sv-muted leading-relaxed mb-4">
                    In enterprise banking, defense, and carrier telecom deployments, SentinelVoice operates <strong>strictly on-premises via local Ollama</strong>:
                  </p>

                  <div className="rounded-lg border border-emerald-500/30 bg-sv-panel p-3 font-mono text-xs space-y-1.5 mb-4 text-sv-fg/90">
                    <div><span className="text-sv-muted">Local Daemon:</span> http://127.0.0.1:11434 (Ollama)</div>
                    <div><span className="text-sv-muted">Deployed Model:</span> JIO_AI2:latest / Llama 3 / Mistral</div>
                    <div><span className="text-sv-muted">Data Egress:</span> <strong className="text-emerald-400">0.00 bytes (Zero Cloud Leakage)</strong></div>
                    <div><span className="text-sv-muted">Isolation:</span> 100% On-Premise / Private Telecom VPC</div>
                  </div>

                  <ul className="text-xs text-sv-muted space-y-1.5">
                    <li className="flex items-center gap-2">
                      <span className="text-emerald-400 font-bold">✓</span>
                      <span><strong>Total Data Sovereignty:</strong> Customer voice audio and transcripts never touch third-party servers.</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <span className="text-emerald-400 font-bold">✓</span>
                      <span><strong>Regulatory Compliance:</strong> Meets RBI guidelines, DPDP Act 2023, and banking privacy standards.</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <span className="text-emerald-400 font-bold">✓</span>
                      <span><strong>Zero Recurring Cloud Cost:</strong> No per-token API charges or external outage vulnerabilities.</span>
                    </li>
                  </ul>
                </div>

                <div className="mt-4 pt-3 border-t border-emerald-500/30 text-[11px] text-emerald-300 font-medium">
                  🔒 Enterprise-grade confidentiality & cryptographic protection
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* ========================================================================= */}
        {/* PLATFORM CAPABILITIES */}
        {/* ========================================================================= */}
        <section id="capabilities" className="scroll-mt-20">
          <div className="text-center max-w-3xl mx-auto mb-8 sm:mb-10">
            <span className="font-mono text-xs font-semibold uppercase tracking-wider text-sv-accent">
              Enterprise Defense Pillars
            </span>
            <h2 className="mt-2 font-display text-2xl sm:text-3xl font-bold text-sv-fg">
              Comprehensive Voice Security Stack
            </h2>
          </div>

          <div className="grid gap-3 sm:gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">🎙️</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Asterisk PJSIP Realtime Telephony
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Connects directly to carrier trunks, PBX softphones, and ConfBridge conference bridges with bi-directional audio tapping and ARI media controls.
              </p>
            </div>

            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">🧠</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Dual-Track Feature Fusion
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Combines high-speed acoustic biomarkers (spectral jitter, energy) with deep semantic intent extraction to prevent false positives on legitimate urgent calls.
              </p>
            </div>

            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">📜</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Dynamic Policy Rule Editor
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Customizable risk rules (<code className="text-sv-accent">R-wire-urgency</code>, <code className="text-sv-accent">R-no-otp</code>) with tenant-scoped thresholds, approval workflows, and audit trails.
              </p>
            </div>

            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">📋</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Cryptographic Forensic Dossiers
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Immutable call records with SHA-256 evidence digests, timeline event breakdown, full transcripts, and downloadable compliance packages.
              </p>
            </div>

            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">📊</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Live Operator Workspace
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Real-time operator monitoring console with live call feeds, spectral waveform visualization, and one-click manual whisper intervention.
              </p>
            </div>

            <div className="rounded-xl border border-sv-border bg-sv-panel p-5">
              <span className="text-2xl">🔐</span>
              <h3 className="mt-3 font-display text-base font-bold text-sv-fg">
                Multi-Tenant Enterprise RBAC
              </h3>
              <p className="mt-1.5 text-xs text-sv-muted leading-relaxed">
                Row-level database security (Postgres RLS), tenant isolation, role-based authorization, MFA authentication, and security audit logs.
              </p>
            </div>
          </div>
        </section>

        {/* ========================================================================= */}
        {/* BOTTOM CALL TO ACTION BANNER */}
        {/* ========================================================================= */}
        <section className="rounded-2xl border border-sv-accent/40 bg-gradient-to-r from-cyan-950/40 via-sv-panel to-indigo-950/40 p-6 sm:p-8 text-center shadow-xl">
          <h2 className="font-display text-xl sm:text-2xl md:text-3xl font-bold text-sv-fg">
            Ready to Explore the SentinelVoice Platform?
          </h2>
          <p className="mx-auto mt-2 max-w-xl text-xs sm:text-sm text-sv-muted">
            Launch directly into the console using the pre-seeded Telco workspace credentials or create your own custom organisation tenant.
          </p>

          <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
            <Button
              variant="primary"
              size="lg"
              onClick={handleOneClickLogin}
              disabled={loggingIn}
              className="w-full sm:w-auto shadow-lg shadow-cyan-500/25 px-6 font-semibold"
            >
              {loggingIn ? 'Logging in…' : '⚡ One-Click Direct Sign In'}
            </Button>
            <Button
              variant="secondary"
              size="lg"
              onClick={() => navigate('/login')}
              className="w-full sm:w-auto px-6"
            >
              Sign In with Custom Account
            </Button>
          </div>
        </section>
      </main>

      {/* Footer */}
      <footer className="mt-12 sm:mt-16 border-t border-sv-border/70 bg-sv-panel/80 py-8 text-xs text-sv-muted">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
          <div>
            <span className="font-semibold text-sv-fg">SentinelVoice</span> · Advanced Telephony & Deepfake Protection Platform
            <div className="text-[11px] text-sv-muted mt-0.5">Built for Smart India Hackathon · Problem Statement 104</div>
          </div>
          <div className="flex flex-wrap items-center gap-3 sm:gap-4">
            <a href="#video-front" className="hover:text-sv-fg transition-colors">Project Video</a>
            <a href="#credentials" className="hover:text-sv-fg transition-colors">Demo Creds</a>
            <a href="#demo-mode" className="hover:text-sv-fg transition-colors">How It Works</a>
            <a href="#llm-architecture" className="hover:text-sv-fg transition-colors">Ollama Privacy</a>
            <Link to="/login" className="hover:text-sv-fg transition-colors">Login</Link>
            <Link to="/register" className="hover:text-sv-fg transition-colors">Register</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

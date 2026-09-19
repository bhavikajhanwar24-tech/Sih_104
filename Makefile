# SentinelVoice — development orchestration
# On Windows without GNU make: use .\make.cmd <target> (wraps scripts/sv.ps1).
# On Linux/macOS with GNU make: these recipes call the same PowerShell script
# when pwsh is available, otherwise fall back to native shell commands.

.DEFAULT_GOAL := help

.PHONY: help ensure-antispoof backend ml frontend asterisk dev demo test eval codec-study clean health

SV_PS1 := scripts/sv.ps1

ifeq ($(OS),Windows_NT)
  SV := powershell -NoProfile -ExecutionPolicy Bypass -File $(SV_PS1)
else
  # Prefer pwsh if present; else native recipes below for non-Windows CI.
  PWSH := $(shell command -v pwsh 2>/dev/null)
  ifneq ($(PWSH),)
    SV := pwsh -NoProfile -File $(SV_PS1)
  else
    SV :=
  endif
endif

help:
ifeq ($(SV),)
	@echo "SentinelVoice - available targets:"
	@echo "  make ensure-antispoof - train/copy Tier-1 voice checkpoint if missing"
	@echo "  make asterisk  - docker compose up -d asterisk"
	@echo "  make ml        - uvicorn Inference Plane :8000"
	@echo "  make backend   - Spring Boot Decision Plane :8080"
	@echo "  make frontend  - Vite Presentation Plane :5173 (strictPort)"
	@echo "  make dev       - all four planes + health waits"
	@echo "  make demo      - preflight + docker compose up --build + seed"
	@echo "  make test      - per-plane test suites"
	@echo "  make eval      - ML benchmark suite"
	@echo "  make clean     - stop processes started by make dev"
	@echo "  make health    - probe health endpoints"
else
	@$(SV) help
endif

ensure-antispoof:
ifeq ($(SV),)
	cd ml-engine && python ../scripts/ensure_antispoof_checkpoint.py
else
	@$(SV) ensure-antispoof
endif

asterisk:
ifeq ($(SV),)
	docker compose up -d asterisk
else
	@$(SV) asterisk
endif

ml:
ifeq ($(SV),)
	cd ml-engine && (test -x .venv/bin/uvicorn && .venv/bin/uvicorn || python -m uvicorn) app.main:app --reload --host 127.0.0.1 --port 8000
else
	@$(SV) ml
endif

backend:
ifeq ($(SV),)
	cd backend && mvn spring-boot:run
else
	@$(SV) backend
endif

frontend:
ifeq ($(SV),)
	cd frontend && npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
else
	@$(SV) frontend
endif

dev:
ifeq ($(SV),)
	@echo "GNU make without pwsh: start planes manually — Media→ml→backend→frontend"
	@echo "On Windows use: .\\make.cmd dev"
	@exit 1
else
	@$(SV) dev
endif

demo:
ifeq ($(SV),)
	bash scripts/preflight.sh
	docker compose up -d --build
	bash scripts/seed_demo.sh
else
	@$(SV) demo
endif

health:
ifeq ($(SV),)
	@curl -sf http://127.0.0.1:8000/health && echo && curl -sf http://127.0.0.1:8080/actuator/health && echo && curl -sf -o /dev/null -w "frontend:%{http_code}\n" http://127.0.0.1:5173/
else
	@$(SV) health
endif

test:
ifeq ($(SV),)
	cd backend && mvn -q test
	cd ml-engine && python -m pytest
	cd frontend && npm test
	@echo "test: gateway — no automated suite yet"
else
	@$(SV) test
endif

eval:
ifeq ($(SV),)
	cd ml-engine && python -m benchmarks.run_eval --limit 40 --synthetic --seed 42 --train-epochs 2
else
	@$(SV) eval
endif

codec-study:
ifeq ($(SV),)
	cd ml-engine && python -m benchmarks.codec_study --limit 40 --synthetic --epochs 4 --seed 42 --retrain
else
	@$(SV) codec-study
endif

clean:
ifeq ($(SV),)
	@echo "clean: stop local uvicorn/java/vite manually, or use .\\make.cmd clean on Windows"
else
	@$(SV) clean
endif

# SentinelVoice — development orchestration
# Targets print TODO until the corresponding Phase lands an implementation.

.DEFAULT_GOAL := help

.PHONY: help dev backend ml frontend asterisk test eval codec-study demo clean

help:
	@echo "SentinelVoice - available targets:"
	@echo "  make dev        - start all services for development"
	@echo "  make backend    - run Spring Boot"
	@echo "  make ml         - run FastAPI with reload"
	@echo "  make frontend   - run Vite dev server"
	@echo "  make asterisk   - start the Asterisk container"
	@echo "  make test       - run all test suites"
	@echo "  make eval       - run the ML benchmark suite"
	@echo "  make codec-study - codec robustness before/after table (§15.3)"
	@echo "  make demo       - seed scenarios and start everything"
	@echo "  make clean      - remove build artifacts"

dev:
	@echo "TODO: start all services for development (docker compose / run_all)"

backend:
	@echo "TODO: run Spring Boot (cd backend && mvn spring-boot:run)"

ml:
	@echo "TODO: run FastAPI with reload (uvicorn app.main:app --reload --port 8000)"

frontend:
	@echo "TODO: run Vite dev server (cd frontend && npm run dev)"

asterisk:
	@echo "TODO: start the Asterisk container (docker compose up asterisk)"

test:
	@echo "TODO: run all test suites (backend + ml-engine + frontend)"

eval:
	cd ml-engine && python -m benchmarks.run_eval --limit 40 --synthetic --seed 42 --train-epochs 2

codec-study:
	cd ml-engine && python -m benchmarks.codec_study --limit 40 --synthetic --epochs 4 --seed 42 --retrain

demo:
	@echo "TODO: seed scenarios and start everything (preflight + up + seed)"

clean:
	@echo "TODO: remove build artifacts (backend/target, frontend/dist, caches)"

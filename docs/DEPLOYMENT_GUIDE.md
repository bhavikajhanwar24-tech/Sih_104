# SentinelVoice — Production Deployment Guide

Refer to [DEPLOYMENT_GUIDE.md](file:///c:/sih/Sih_104/DEPLOYMENT_GUIDE.md) in the project root for full instructions.

## Quick Summary
- **Frontend**: Deploy `frontend/` on **Vercel** with `VITE_API_BASE_URL=https://<your-backend>.onrender.com`.
- **Backend**: Deploy `backend/` on **Render** (using `render.yaml` or Docker Web Service) with Java 21 / Spring Boot 3.
- **Database**: Connect to Supabase or Render PostgreSQL using JDBC format.

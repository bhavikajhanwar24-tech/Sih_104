# SentinelVoice — Production Deployment Guide

This guide provides step-by-step instructions to deploy the **Frontend on Vercel** and the **Backend on Render**, backed by **PostgreSQL** (Supabase or Render Postgres).

---

## 🏗️ Architecture Overview

```mermaid
flowchart TD
    User["🌐 User Browser"] -->|"HTTPS / UI Navigation"| Vercel["⚡ Vercel (Frontend SPA)"]
    User -->|"REST API (with Cookies/CSRF)"| Render["🚀 Render (Spring Boot Backend)"]
    User -->|"WSS /ws-sentinel (STOMP)"| Render
    Render -->|"JDBC (Port 5432 / TLS)"| DB[("🐘 PostgreSQL (Supabase / Render)")]
    Render -->|"HTTPS Inference"| LLM["🤖 Groq / LLM Cloud Gateway"]
```

| Component | Platform | Tech Stack | Production URL Example |
| :--- | :--- | :--- | :--- |
| **Frontend** | **Vercel** | React 18, Vite 5, TailwindCSS, STOMP | `https://sentinelvoice-web.vercel.app` |
| **Backend** | **Render** | Spring Boot 3, Java 21, Flyway | `https://sentinelvoice-backend.onrender.com` |
| **Database** | **Supabase / Render** | PostgreSQL 16 | AWS / Cloud Managed |
| **LLM Gateway** | **Groq Cloud** | LLaMA / DeepSeek / Mistral | `https://api.groq.com/openai` |

---

## 📋 Pre-Deployment Checklist

1. [x] Git repository pushed to GitHub or GitLab.
2. [x] Free accounts on [Vercel](https://vercel.com) and [Render](https://render.com).
3. [x] PostgreSQL instance on [Supabase](https://supabase.com) (Recommended) or Render.

---

## Step 1: Database Setup (Supabase or Render Postgres)

### Option A: Supabase (Recommended — Free & Fast)
1. Go to [database.new](https://database.new) and create a project.
2. Under **Project Settings -> Database -> Connection string**:
   - Select **Session pooler** (Port 5432).
   - Mode: `Session`.
   - Copy the connection URI:
     `postgresql://postgres.[REF]:[PASSWORD]@aws-0-[REGION].pooler.supabase.com:5432/postgres?sslmode=require`
3. Convert to JDBC format for Spring Boot:
   ```env
   DB_URL=jdbc:postgresql://aws-0-[REGION].pooler.supabase.com:5432/postgres?sslmode=require&options=-c%20TimeZone%3DUTC
   DB_APP_USER=postgres.[REF]
   DB_APP_PASSWORD=<YOUR_SUPABASE_PASSWORD>
   DB_OWNER_USER=postgres.[REF]
   DB_OWNER_PASSWORD=<YOUR_SUPABASE_PASSWORD>
   ```
*(Flyway will automatically create all tables and run migrations on backend startup!)*

### Option B: Render Managed PostgreSQL
1. On Render Dashboard, click **New +** -> **PostgreSQL**.
2. Name: `sentinelvoice-db`, Database: `sentinelvoice`, User: `sv_owner`.
3. After creation, copy the **External Database URL**.
4. Convert `postgres://` or `postgresql://` to `jdbc:postgresql://` with `?sslmode=require`.

---

## Step 2: Deploy Backend on Render

### Method 1: Using `render.yaml` Blueprint (Fastest)
1. In the Render Dashboard, click **Blueprints** -> **New Blueprint Instance**.
2. Select your repository.
3. Render will parse `render.yaml` and configure the service automatically.
4. Fill in your `DB_URL`, `DB_APP_PASSWORD`, `DB_OWNER_PASSWORD`, and `SENTINELVOICE_CORS_ORIGINS`.
5. Click **Apply**.

---

### Method 2: Manual Web Service Setup on Render
1. In Render Dashboard, click **New +** -> **Web Service**.
2. Connect your Git repository.
3. Configure the settings:
   - **Name**: `sentinelvoice-backend`
   - **Region**: Nearest to your database (e.g. Frankfurt, Singapore, Oregon)
   - **Branch**: `main`
   - **Root Directory**: Leave blank (or `backend`)
   - **Runtime**: `Docker`
   - **Dockerfile Path**: `./backend/Dockerfile` (or `./Dockerfile`)
   - **Instance Type**: `Free` or `Starter`
   - **Health Check Path**: `/actuator/health`

4. Add the following **Environment Variables**:

| Variable | Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8081` | Dynamic port (Render auto-detects) |
| `COOKIE_SECURE` | `true` | Enables secure cookies over HTTPS |
| `COOKIE_SAME_SITE` | `None` | Allows cross-origin cookies between Vercel & Render |
| `JWT_SECRET` | *(Random 32+ char string)* | Key for signing JWT tokens |
| `APP_ENCRYPTION_KEY` | *(Random 32+ char string)* | AES encryption key for integration secrets |
| `ML_SERVICE_TOKEN` | *(Random 16+ char string)* | Internal service communication secret |
| `SENTINELVOICE_CORS_ORIGINS` | `https://your-frontend-app.vercel.app` | Your Vercel frontend URL |
| `DB_URL` | `jdbc:postgresql://...` | JDBC Connection URL |
| `DB_APP_USER` | `postgres` (or `sv_app`) | Database application user |
| `DB_APP_PASSWORD` | `<your-db-password>` | Database application password |
| `DB_OWNER_USER` | `postgres` (or `sv_owner`) | Flyway migration owner |
| `DB_OWNER_PASSWORD` | `<your-db-password>` | Flyway migration password |
| `GROQ_API_KEY` | *(Optional)* | For cloud LLM policy analysis |
| `LAB_MODE` | `false` | Set to false for production |

5. Click **Create Web Service**.
6. Wait 2-3 minutes for the build to finish. Once deployed, note down your Render backend URL (e.g., `https://sentinelvoice-backend.onrender.com`).

---

## Step 3: Deploy Frontend on Vercel

1. Log into [Vercel](https://vercel.com) and click **Add New...** -> **Project**.
2. Import your Git repository.
3. Configure the project:
   - **Framework Preset**: `Vite`
   - **Root Directory**: Click *Edit* and select `frontend` (or leave default if deploying root).
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
4. Expand **Environment Variables** and add:

| Name | Value | Description |
| :--- | :--- | :--- |
| `VITE_API_BASE_URL` | `https://sentinelvoice-backend.onrender.com` | Your deployed Render backend URL |
| `VITE_STOMP_URL` | `wss://sentinelvoice-backend.onrender.com/ws-sentinel` | *(Optional, auto-derived from API URL)* |

5. Click **Deploy**.
6. Once deployed, copy your Vercel URL (e.g., `https://sentinelvoice-web.vercel.app`).

> ⚠️ **Important Step**:
> Go back to your **Render Backend Dashboard** -> **Environment Variables**, update `SENTINELVOICE_CORS_ORIGINS` with your actual Vercel URL (`https://sentinelvoice-web.vercel.app`), and click **Save Changes**.

---

## Step 4: Verification & Smoke Test

### 1. Check Backend Health
Open in your browser:
```
https://sentinelvoice-backend.onrender.com/actuator/health
```
**Expected response**: `{"status":"UP"}`

### 2. Check API Documentation
Open:
```
https://sentinelvoice-backend.onrender.com/api/v2/docs
```
You should see the interactive Swagger UI with all v1 & v2 endpoints.

### 3. Test Frontend Authentication & Features
1. Open your Vercel app URL (`https://sentinelvoice-web.vercel.app`).
2. Log in with your admin credentials (default initial lab credentials: `admin@sentinelvoice.internal` / `password`).
3. Verify that:
   - Status bar shows connected WebSocket STOMP indicator (`CONNECTED`).
   - Call detail telemetry and policies load seamlessly.
   - Forensic Dossiers PDF download works.

---

## 🛠️ Troubleshooting & FAQs

### Q: Why do I get a CORS error on login?
- Ensure `SENTINELVOICE_CORS_ORIGINS` in Render contains your exact Vercel URL (including `https://` with no trailing slash). Note: Subdomains `https://*.vercel.app` are automatically permitted.

### Q: Why am I getting logged out or 401 Unauthorized?
- Ensure `COOKIE_SECURE=true` and `COOKIE_SAME_SITE=None` are set on Render so browsers allow cross-domain cookies between Vercel and Render over HTTPS.

### Q: Why does Render Free Web Service go to sleep?
- Render free tier instances spin down after 15 minutes of inactivity. The first request after sleep takes ~30-50 seconds. For zero downtime, upgrade to Render Starter plan ($7/mo) or use a free uptime pinger (e.g. UptimeRobot) pinging `/actuator/health` every 10 minutes.

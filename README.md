# SentinelVoice Backend

This repository contains the Java Spring Boot backend for the SentinelVoice fraud-detection and voice verification platform.

## Tech stack
- Java 21
- Spring Boot 3.3.4
- Maven
- Spring Web
- Spring Data JPA
- H2 in-memory database
- Spring Security
- WebSocket support

## Project structure

- `backend/` - main Spring Boot application
  - `src/main/java` - Java source files
  - `src/main/resources` - app config and static resources
  - `src/test/java` - tests

## Run locally

```powershell
cd backend
& "C:\Users\Bhavika Jhanwar\Desktop\Sih_104\apache-maven-3.9.9\bin\mvn.cmd" spring-boot:run
```

The app runs on:

- `http://localhost:8082`

## Main API endpoints

- `POST /api/v1/session/start`
- `GET /api/v1/session/{sessionId}`
- `POST /api/v1/session/{sessionId}/simulate`
- `POST /api/v1/stream/{sessionId}/audio`
- `POST /api/v1/challenge/issue`
- `POST /api/v1/challenge/verify`
- `GET /api/v1/compliance/audit-chain`
- `GET /api/v1/cross-channel`
- `GET /api/v1/forensics/{sessionId}/dossier`
- `POST /api/v1/passport/register`
- `DELETE /api/v1/passport/{profileId}`

## Notes

- This project uses H2 in-memory storage for local development.
- The default app port is `8082`.

## GitHub push

After creating the remote repository on GitHub, run:

```bash
git remote add origin <your-github-repo-url>
git branch -M main
git push -u origin main
```

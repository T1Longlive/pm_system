# PM System

## Introduction

The PM System is a project management system designed for IT teams, covering core business scenarios such as **requirement management, development scheduling, bug tracking, and project collaboration**. The system adopts a modern front-end and back-end separated architecture, striving to improve development efficiency through structured data models (such as requirement state machines and monthly schedule grids).

## Main Features

1.  **Requirement Full Lifecycle Management**: Supports requirement creation, assignment, participant management, status transitions (To Do/Ready/Development/Test/Archived), and operation log tracing.
2.  **Visual Monthly Scheduling**: Provides a calendar-based scheduling view, supports allocating man-hours by discipline (FE/BE/QA, etc.), and handles schedule overflow logic.
3.  **Bug Tracking & Excel-style Editing**: Supports the full bug handling process (Acceptance, Resolution, Verification) and provides an Excel-like batch processing model.
4.  **Project & Member Management**: Maintains project members and roles (Product, Frontend, Backend, Testing, etc.), supporting flexible permission control.
5.  **In-App Notification System**: Pushes in-system messages for key events such as requirement status changes and bug assignments.
6.  **Unified Authentication & Authorization**: Based on a JWT front-end and back-end separated authentication architecture.

## Tech Stack

*   **Backend**: Java 21, Spring Boot 3, MyBatis-Plus, Redis, MySQL
*   **Frontend**: Vue 3, Vue Router, Pinia, Axios
*   **Documentation**: Detailed architecture and development documents are located in `docs/code-wiki/`

## Project Structure

```
pm-system/
├── backend/                # Spring Boot backend project
│   ├── src/main/java/com/pm/
│   │   ├── controller/     # REST API controllers
│   │   ├── service/        # Business logic layer
│   │   ├── mapper/         # Data access layer
│   │   ├── entity/         # Data entities
│   │   ├── dto/            # Data transfer objects
│   │   ├── vo/             # View objects
│   │   └── security/       # Security and authentication components
│   └── src/main/resources/
│       ├── application.yml # Configuration file
│       └── db/             # Database initialization scripts
├── frontend/               # Vue 3 frontend project
│   ├── src/
│   │   ├── api/            # API encapsulation
│   │   ├── views/          # Page components
│   │   ├── components/     # Common components
│   │   └── stores/         # State management
│   └── package.json
└── docs/code-wiki/         # Detailed development documentation
```

## Quick Start

### Environment Requirements

*   JDK 21+
*   Node.js 18+
*   MySQL 8.0+ (Recommended: root/123456, or modify according to configuration)
*   Redis (Recommended password: 123456)

### 1. Database Initialization

Create the database and import the initialization script:

```sql
CREATE DATABASE pm_system DEFAULT CHARACTER SET utf8mb4;
USE pm_system;
-- Import backend/src/main/resources/db/init.sql
```

### 2. Start Backend

The backend listens on port **8080** by default.

```bash
cd backend
# Windows
mvnw.cmd spring-boot:run
# Linux/macOS
./mvnw spring-boot:run
```

> **Note**: Please ensure that the database connection information (username, password) in `application.yml` matches your local environment.

### 3. Start Frontend

The frontend listens on port **5173** by default.

```bash
cd frontend
npm install
npm run dev
```

### 4. Access System

Open [http://localhost:5173](http://localhost:5173) in your browser.

Default administrator account: `admin`
Default administrator password: `Pm@123456`

## Documentation Navigation

*   **Architecture Design**: [docs/code-wiki/01-architecture.md](docs/code-wiki/01-architecture.md)
*   **Backend Modules & APIs**: [docs/code-wiki/02-backend-modules.md](docs/code-wiki/02-backend-modules.md)
*   **Database Design**: [docs/code-wiki/03-database-design.md](docs/code-wiki/03-database-design.md)
*   **Security & Authentication**: [docs/code-wiki/04-security-auth.md](docs/code-wiki/04-security-auth.md)
*   **Deployment Guide**: [docs/code-wiki/08-run-deploy.md](docs/code-wiki/08-run-deploy.md)

## License

This project is for learning and internal use only.
# TraceMind — AI-Powered Project Intelligence Platform

TraceMind is a full-stack developer tool designed to help developers understand software repositories, explore project structure, analyze dependencies, and assess the potential impact of code changes.

The goal is to make complex codebases easier to navigate by turning repository information into useful, structured insights.

> **Project status:** Active development. Repository management is implemented; automated repository analysis, dependency mapping, and AI-powered impact analysis are planned enhancements.

## Key Features

### Implemented
- **Repository Management:** Add and view GitHub repositories.
- **Input Validation:** Validate repository names and GitHub HTTPS URLs.
- **Duplicate Prevention:** Prevent adding the same repository URL twice through the frontend.
- **REST API:** Create, retrieve, and delete saved repository records.
- **Persistent Storage:** Store repository information in PostgreSQL.
- **Error Handling:** Return structured validation errors from the backend.
- **Health Check:** Check backend service availability.

### Planned
- **Repository Analysis:** Retrieve repository metadata and identify programming languages.
- **Codebase Exploration:** Display repository files and directory structure.
- **Dependency Mapping:** Identify dependencies and relationships between project components.
- **Change Impact Analysis:** Identify components that may be affected by code changes.
- **AI-Powered Insights:** Generate understandable explanations of repository structure and potential change risks.

## Technology Stack

| Layer | Technologies |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Java, Spring Boot, Spring Web |
| Database | PostgreSQL |
| Data Access | Spring Data JPA, Hibernate |
| Validation | Jakarta Bean Validation |
| API | REST |
| Build Tool | Maven |
| Version Control | Git and GitHub |

## Architecture

```text
Developer
    |
    v
React + TypeScript Frontend
    |
    | HTTP / REST API
    v
Spring Boot Backend
    |
    | Spring Data JPA / Hibernate
    v
PostgreSQL Database
```

The frontend provides the user interface, the Spring Boot backend handles requests and business logic, and PostgreSQL stores repository information.

## Prerequisites

Install the following before running TraceMind locally:

- **Git** — to manage the source code.
- **Node.js and npm** — to run the frontend.
- **Java JDK** — use a version compatible with the backend's `pom.xml`.
- **PostgreSQL** — to store repository records.
- **Terminal or an IDE** — such as Visual Studio Code or IntelliJ IDEA.

The backend includes a Maven Wrapper (`mvnw`), so a separate Maven installation is not normally required.

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/Aditya957258/TraceMind.git
cd TraceMind
```

### 2. Configure PostgreSQL

Create a PostgreSQL database named `tracemind_db` and a database user named `tracemind_user` if they do not already exist.

The database connection is configured in:

`backend/src/main/resources/application.properties`

The application expects these settings:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/tracemind_db
spring.datasource.username=tracemind_user
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=update
```

Set `DB_PASSWORD` to the password for your PostgreSQL user. Do not commit database passwords or other secrets to GitHub.

### 3. Start the backend

Open a terminal in the project directory and run:

```bash
cd backend
read -s "DB_PASSWORD?Enter your database password: "
export DB_PASSWORD
echo
./mvnw spring-boot:run
```

The backend should start at:

`http://localhost:8080`

Keep this terminal running.

### 4. Start the frontend

Open a **second terminal**:

```bash
cd ~/Desktop/TraceMind/frontend
npm install
npm run dev
```

If you cloned the project into a different location, navigate to its `frontend` directory instead.

Open the local frontend URL reported by Vite, usually:

`http://localhost:5173`

Keep the frontend and backend running in separate terminals.

### 5. Verify the backend

Check the health endpoint:

```bash
curl http://localhost:8080/api/v1/health
```

Retrieve saved repositories:

```bash
curl http://localhost:8080/api/v1/projects
```

The projects endpoint returns repository records stored in PostgreSQL.

## REST API Reference

Base URL: `http://localhost:8080`

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/v1/health` | Check backend health |
| GET | `/api/v1/projects` | Retrieve saved repositories |
| POST | `/api/v1/projects` | Add a repository |
| DELETE | `/api/v1/projects/{id}` | Delete a repository by ID |

### Add a repository

**Request**

```http
POST /api/v1/projects
Content-Type: application/json
```

**Example body**

```json
{
  "name": "TraceMind",
  "description": "AI-powered project intelligence platform",
  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
}
```

**Example command**

```bash
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name": "TraceMind",
    "description": "AI-powered project intelligence platform",
    "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
  }'
```

A successfully created repository returns HTTP `201 Created`. Invalid input returns a validation error.

## Project Structure

```text
TraceMind/
├── backend/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/tracemind/backend/
│   │       └── resources/
│   │           └── application.properties
│   └── pom.xml
├── frontend/
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
├── .gitignore
└── README.md
```

The directory structure above highlights the main application components; additional files may be present.

## Development Roadmap

- [x] Establish the frontend and backend foundation.
- [x] Integrate PostgreSQL persistence.
- [x] Implement repository creation, listing, and deletion APIs.
- [x] Add request validation and structured error handling.
- [x] Integrate repository management with the frontend.
- [ ] Retrieve GitHub repository metadata.
- [ ] Analyze repository files and programming languages.
- [ ] Build dependency maps and component relationships.
- [ ] Implement code change impact analysis.
- [ ] Add AI-powered explanations and risk insights.
- [ ] Expand automated testing and deployment.

## Security and Configuration

- Keep passwords, API tokens, and private credentials out of source control.
- Use environment variables for sensitive configuration.
- Validate incoming API requests.
- Review GitHub access permissions before integrating private repositories.
- Configure authentication and authorization before exposing sensitive operations publicly.

## Contributing

TraceMind is under active development. Contributions, issue reports, and suggestions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make and test your changes.
4. Submit a pull request describing the changes.

## Author

**Aditya Kumar**

GitHub: [@Aditya957258](https://github.com/Aditya957258)

**Repository:** [Aditya957258/TraceMind](https://github.com/Aditya957258/TraceMind)

---

*TraceMind aims to make software repositories easier to understand, navigate, and maintain through intelligent project insights.*
# TraceMind
AI-powered project intelligence platform for repository analysis and change impact insights.

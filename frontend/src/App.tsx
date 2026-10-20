
import { useCallback, useEffect, useState, type FormEvent } from "react";
import "./App.css";

const API_BASE = "http://localhost:8080/api/v1";

type ApiHealth = {
  service: string;
  status: string;
};

type Project = {
  id: number;
  name: string;
  description: string | null;
  repositoryUrl: string;
  createdAt: string;
};

type ApiErrorResponse = {
  status?: number;
  error?: string;
  message?: string;
  messages?: Record<string, string>;
};

type FormMessageType = "success" | "error";

function isValidGitHubRepositoryUrl(url: string): boolean {
  return /^https:\/\/github\.com\/[A-Za-z0-9-]+\/[A-Za-z0-9_.-]+\/?$/.test(
    url.trim(),
  );
}

function normalizeRepositoryUrl(url: string): string {
  return url.trim().replace(/\/+$/, "").toLowerCase();
}

function getErrorMessage(data: unknown, status: number): string {
  if (data && typeof data === "object") {
    const apiError = data as ApiErrorResponse;

    if (apiError.messages && typeof apiError.messages === "object") {
      const messages = Object.values(apiError.messages).filter(
        (item): item is string =>
          typeof item === "string" && item.trim().length > 0,
      );

      if (messages.length > 0) {
        return messages.join(" ");
      }
    }

    if (apiError.message) return apiError.message;
    if (apiError.error) return apiError.error;
  }

  return `Request failed (HTTP ${status}). Please try again.`;
}

async function readResponse(response: Response): Promise<unknown> {
  return response.json().catch(() => null);
}

function App() {
  const [health, setHealth] = useState<ApiHealth | null>(null);
  const [healthError, setHealthError] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [messageType, setMessageType] =
    useState<FormMessageType>("success");
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteError, setDeleteError] = useState("");

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [repositoryUrl, setRepositoryUrl] = useState("");

  const loadProjects = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const response = await fetch(`${API_BASE}/projects`);

      if (!response.ok) {
        const data = await readResponse(response);
        throw new Error(getErrorMessage(data, response.status));
      }

      const data: unknown = await readResponse(response);

      if (!Array.isArray(data)) {
        throw new Error("The server returned an invalid project list.");
      }

      setProjects(data as Project[]);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Could not load repositories. Check your backend connection.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;

    async function checkHealth() {
      try {
        const response = await fetch(`${API_BASE}/health`);

        if (!response.ok) {
          throw new Error("Backend unavailable");
        }

        const data: ApiHealth = await response.json();

        if (active) {
          setHealth(data);
          setHealthError("");
        }
      } catch {
        if (active) {
          setHealth(null);
          setHealthError("Backend unavailable");
        }
      }
    }

    void checkHealth();
    void loadProjects();

    return () => {
      active = false;
    };
  }, [loadProjects]);

  function clearFormMessage() {
    setMessage("");
  }

  async function handleCreateProject(
    event: FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();
    setMessage("");

    const trimmedName = name.trim();
    const trimmedDescription = description.trim();
    const trimmedUrl = repositoryUrl.trim();

    if (!trimmedName) {
      setMessage("Please enter a project name.");
      setMessageType("error");
      return;
    }

    if (trimmedName.length > 255) {
      setMessage("Project name cannot exceed 255 characters.");
      setMessageType("error");
      return;
    }

    if (!isValidGitHubRepositoryUrl(trimmedUrl)) {
      setMessage(
        "Enter a valid HTTPS GitHub repository URL, such as https://github.com/username/repository",
      );
      setMessageType("error");
      return;
    }

    if (trimmedDescription.length > 2000) {
      setMessage("Description cannot exceed 2000 characters.");
      setMessageType("error");
      return;
    }

    const normalizedUrl = normalizeRepositoryUrl(trimmedUrl);

    const alreadyExists = projects.some(
      (project) =>
        normalizeRepositoryUrl(project.repositoryUrl) === normalizedUrl,
    );

    if (alreadyExists) {
      setMessage(
        "This repository is already in your workspace. Refresh the list to check the latest data.",
      );
      setMessageType("error");
      return;
    }

    setSubmitting(true);
    setMessageType("success");

    try {
      const response = await fetch(`${API_BASE}/projects`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          name: trimmedName,
          description: trimmedDescription || null,
          repositoryUrl: trimmedUrl,
        }),
      });

      const data: unknown = await readResponse(response);

      if (!response.ok) {
        throw new Error(getErrorMessage(data, response.status));
      }

      if (
        !data ||
        typeof data !== "object" ||
        !("id" in data) ||
        !("name" in data) ||
        !("repositoryUrl" in data)
      ) {
        throw new Error(
          "The server returned an unexpected response. Refresh your workspace.",
        );
      }

      const savedProject = data as Project;

      // Keep the UI synchronized without adding the same record twice.
      setProjects((current) => {
        const exists = current.some(
          (project) =>
            project.id === savedProject.id ||
            normalizeRepositoryUrl(project.repositoryUrl) ===
              normalizeRepositoryUrl(savedProject.repositoryUrl),
        );

        if (exists) return current;

        return [savedProject, ...current];
      });

      setName("");
      setDescription("");
      setRepositoryUrl("");
      setMessage("Repository added successfully.");
      setMessageType("success");
      setError("");
    } catch (err) {
      setMessage(
        err instanceof Error
          ? err.message
          : "Could not save the repository. Check your backend connection.",
      );
      setMessageType("error");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeleteProject(project: Project) {
    const confirmed = window.confirm(
      `Are you sure you want to delete "${project.name}"?`,
    );

    if (!confirmed) return;

    setDeletingId(project.id);
    setDeleteError("");

    try {
      const response = await fetch(
        `${API_BASE}/projects/${project.id}`,
        { method: "DELETE" },
      );

      if (!response.ok) {
        const data = await readResponse(response);
        throw new Error(
          response.status === 404
            ? "Repository not found. Refresh the list."
            : getErrorMessage(data, response.status),
        );
      }

      setProjects((current) =>
        current.filter((item) => item.id !== project.id),
      );

      setDeleteError("");
    } catch (err) {
      setDeleteError(
        err instanceof Error
          ? err.message
          : "Could not delete the repository. Check your backend connection.",
      );
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#overview">
          <div className="brand-mark">T</div>
          <div className="brand-copy">
            <h1>TraceMind</h1>
            <p>DEVELOPER INTELLIGENCE</p>
          </div>
        </a>

        <div className="workspace-switcher">
          <span className="workspace-avatar">AK</span>
          <span className="workspace-copy">
            <strong>Personal workspace</strong>
            <small>Free workspace</small>
          </span>
          <span className="switcher-arrow">⌄</span>
        </div>

        <p className="nav-label">WORKSPACE</p>
        <nav className="sidebar-nav">
          <a className="nav-item active" href="#overview">
            <span>◫</span> Overview
          </a>
          <a className="nav-item" href="#projects">
            <span>⑂</span> Repositories
            <span className="nav-count">{projects.length}</span>
          </a>
          <a className="nav-item" href="#insights">
            <span>✳</span> AI insights
            <span className="soon-tag">SOON</span>
          </a>
        </nav>

        <p className="nav-label tools-label">DEVELOPER TOOLS</p>
        <nav className="sidebar-nav">
          <a className="nav-item" href="#projects">
            <span>⌘</span> Project explorer
          </a>
          <a className="nav-item" href="#add-project">
            <span>＋</span> Add repository
          </a>
        </nav>

        <div className="sidebar-bottom">
          <div className="sidebar-status">
            <span
              className={`status-dot ${health ? "is-online" : ""}`}
            />
            <span>
              <strong>
                {health ? "Systems operational" : "Checking systems"}
              </strong>
              <small>TraceMind API</small>
            </span>
          </div>

          <div className="profile-row">
            <div className="profile-avatar">AK</div>
            <div className="profile-copy">
              <strong>Aditya Kumar</strong>
              <span>Full-Stack Developer</span>
              <small>Personal account</small>
            </div>
            <span className="profile-menu" title="Personal workspace">
              ···
            </span>
          </div>
        </div>
      </aside>

      <main className="main-area" id="overview">
        <header className="topbar">
          <div className="breadcrumbs">
            <span>Workspace</span>
            <span className="breadcrumb-separator">/</span>
            <strong>Overview</strong>
          </div>

          <div className="topbar-actions">
            <span className={`api-badge ${health ? "connected" : ""}`}>
              <span className="status-dot" />
              {health ? "API connected" : healthError || "Connecting"}
            </span>

            <a
              className="topbar-github"
              href="https://github.com/Aditya957258"
              target="_blank"
              rel="noreferrer"
            >
              GitHub ↗
            </a>
          </div>
        </header>

        <div className="page-content">
          <section className="welcome-section">
            <div>
              <div className="eyebrow">
                <span className="eyebrow-sparkle">✳</span>
                YOUR DEVELOPER WORKSPACE
              </div>
              <h1>
                Build with clarity<span className="title-period">.</span>
              </h1>
              <p>
                Understand your codebase. Discover connections.
                <br className="desktop-break" /> Make every change with
                confidence.
              </p>
            </div>

            <a className="primary-button" href="#add-project">
              <span>＋</span> Add repository
            </a>
          </section>

          <section className="stats-grid">
            <article className="stat-card">
              <div className="stat-topline">
                <span className="stat-icon purple-icon">⑂</span>
                <span className="stat-caption">WORKSPACE</span>
              </div>
              <div className="stat-value">
                {projects.length.toString().padStart(2, "0")}
              </div>
              <div className="stat-bottom">
                <strong>Repositories</strong>
                <span>Tracked projects</span>
              </div>
            </article>

            <article className="stat-card">
              <div className="stat-topline">
                <span className="stat-icon green-icon">✓</span>
                <span className="stat-caption">CONNECTIVITY</span>
              </div>
              <div className="stat-value stat-word">
                {health ? "Healthy" : "Checking"}
              </div>
              <div className="stat-bottom">
                <strong>Backend status</strong>
                <span>{health?.service ?? "Spring Boot API"}</span>
              </div>
            </article>

            <article className="stat-card">
              <div className="stat-topline">
                <span className="stat-icon blue-icon">✳</span>
                <span className="stat-caption">INTELLIGENCE</span>
              </div>
              <div className="stat-value stat-word">Coming soon</div>
              <div className="stat-bottom">
                <strong>AI code analysis</strong>
                <span>Next development milestone</span>
              </div>
            </article>
          </section>

          <section className="insights-panel" id="insights">
            <div className="insights-glow" />
            <div className="insights-content">
              <div className="insights-label">
                <span>✳</span> INTRODUCING TRACEMIND INTELLIGENCE
              </div>
              <h2>Your code has a story.</h2>
              <p>
                We're building repository intelligence to help you understand
                project structure, dependencies, and the potential impact of
                changes.
              </p>
              <div className="insights-features">
                <span>
                  <i>✓</i> Repository understanding
                </span>
                <span>
                  <i>✓</i> Dependency mapping
                </span>
                <span>
                  <i>✓</i> Change impact analysis
                </span>
              </div>
            </div>

            <div className="insights-visual" aria-hidden="true">
              <div className="orbit orbit-one" />
              <div className="orbit orbit-two" />
              <div className="orbit orbit-three" />
              <div className="orbit-core">T</div>
              <span className="orbit-node node-one">{"</>"}</span>
              <span className="orbit-node node-two">⑂</span>
              <span className="orbit-node node-three">✳</span>
            </div>
            <span className="preview-badge">IN DEVELOPMENT</span>
          </section>

          <section className="repositories-section" id="projects">
            <div className="section-heading">
              <div>
                <div className="eyebrow">YOUR CODEBASES</div>
                <h2>
                  Repositories{" "}
                  <span className="heading-count">{projects.length}</span>
                </h2>
                <p>All your tracked projects, in one workspace.</p>
              </div>

              <button
                type="button"
                className="secondary-button"
                onClick={() => void loadProjects()}
                disabled={loading}
              >
                <span className={loading ? "refreshing" : ""}>↻</span>
                {loading ? "Refreshing" : "Refresh"}
              </button>
            </div>

            {error && (
              <div className="notice-error" role="alert">
                {error}
              </div>
            )}

            {deleteError && (
              <div className="notice-error" role="alert">
                {deleteError}
              </div>
            )}

            {loading ? (
              <div className="empty-panel">
                Loading your repositories…
              </div>
            ) : projects.length === 0 ? (
              <div className="empty-panel">
                <div className="empty-symbol">⑂</div>
                <h3>Your workspace starts here</h3>
                <p>
                  Add a repository to start building your TraceMind workspace.
                </p>
                <a className="primary-button" href="#add-project">
                  Add your first repository
                </a>
              </div>
            ) : (
              <div className="repository-grid">
                {projects.map((project) => (
                  <article className="repository-card" key={project.id}>
                    <div className="repository-card-header">
                      <div className="repository-symbol">{"</>"}</div>
                      <span className="repository-status">
                        <span className="status-dot is-online" /> CONNECTED
                      </span>
                      <span className="repository-menu">···</span>
                    </div>

                    <h3>{project.name}</h3>
                    <p className="repository-description">
                      {project.description ||
                        "No project description added yet."}
                    </p>

                    <div className="repository-divider" />

                    <div className="repository-meta">
                      <span>PROJECT #{project.id}</span>
                      <span>
                        {new Date(project.createdAt).toLocaleDateString()}
                      </span>
                    </div>

                    <a
                      className="repository-link"
                      href={project.repositoryUrl}
                      target="_blank"
                      rel="noreferrer"
                    >
                      View on GitHub <span>↗</span>
                    </a>

                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => void handleDeleteProject(project)}
                      disabled={deletingId !== null}
                      aria-label={`Delete ${project.name}`}
                    >
                      {deletingId === project.id
                        ? "Deleting…"
                        : "Delete repository"}
                    </button>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="add-section" id="add-project">
            <div className="add-section-intro">
              <div className="eyebrow">EXPAND YOUR WORKSPACE</div>
              <h2>Bring a project in.</h2>
              <p>
                Register a repository to keep your projects organized in
                TraceMind.
              </p>
              <div className="form-note">
                <span>⌁</span>
                Your project details are stored in your PostgreSQL database.
              </div>
            </div>

            <form
              className="repository-form"
              onSubmit={handleCreateProject}
              noValidate
            >
              <div className="form-heading">
                <span className="form-heading-icon">＋</span>
                <div>
                  <h3>New repository</h3>
                  <p>Enter your repository details below.</p>
                </div>
              </div>

              <label htmlFor="project-name">
                Project name <span>*</span>
              </label>
              <input
                id="project-name"
                type="text"
                value={name}
                onChange={(event) => {
                  setName(event.target.value);
                  clearFormMessage();
                }}
                placeholder="e.g. My developer tool"
                maxLength={255}
                autoComplete="off"
                required
              />

              <label htmlFor="repository-url">
                GitHub repository URL <span>*</span>
              </label>
              <input
                id="repository-url"
                type="text"
                inputMode="url"
                value={repositoryUrl}
                onChange={(event) => {
                  setRepositoryUrl(event.target.value);
                  clearFormMessage();
                }}
                placeholder="https://github.com/username/repository"
                maxLength={2048}
                autoComplete="url"
                spellCheck={false}
                required
              />

              <label htmlFor="project-description">Description</label>
              <textarea
                id="project-description"
                value={description}
                onChange={(event) => {
                  setDescription(event.target.value);
                  clearFormMessage();
                }}
                placeholder="What does this project do?"
                maxLength={2000}
                rows={3}
              />

              <button
                className="primary-button form-submit"
                type="submit"
                disabled={submitting}
              >
                {submitting ? "Saving repository…" : "Add to workspace"}
                {!submitting && <span>→</span>}
              </button>

              {message && (
                <p
                  className={`form-message ${
                    messageType === "error"
                      ? "error-message"
                      : "success-message"
                  }`}
                  role={messageType === "error" ? "alert" : "status"}
                >
                  {message}
                </p>
              )}
            </form>
          </section>

          <footer className="app-footer">
            <span>
              TraceMind <span className="footer-dot">·</span> Built for safer
              software development
            </span>
            <span className="footer-version">
              LOCAL DEVELOPMENT <span>•</span> V1.0
            </span>
          </footer>
        </div>
      </main>
    </div>
  );
}

export default App;

import {
  useCallback,
  useEffect,
  useState,
  type FormEvent,
} from "react";
import "./App.css";

const API_BASE =
  "https://tracemind-api-aditya-5ivx.onrender.com/api/v1";

const GITHUB_REPOSITORY_PATTERN = /^[A-Za-z0-9_.-]+$/;

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

type RepositoryAnalysis = {
  name: string;
  fullName: string;
  description: string | null;
  htmlUrl: string;
  defaultBranch: string;
  stars: number;
  forks: number;
  openIssues: number;
  primaryLanguage: string | null;
  languages: Record<string, number>;
};

type StructureEntry = {
  path: string;
  type: "blob" | "tree";
  size: number | null;
};

type RepositoryStructure = {
  repository: string;
  defaultBranch: string;
  totalEntries: number;
  fileCount: number;
  directoryCount: number;
  truncated: boolean;
  summary: {
    topLevelFolders: string[];
    importantFiles: string[];
    detectedTechnologies: string[];
    detectedAreas: string[];
    explanation: string;
  };
  entries: StructureEntry[];
};

type Dependency = {
  source: string;
  import: string;
  kind: "internal" | "external";
  target?: string;
};

type UnresolvedImport = {
  source: string;
  import: string;
};

type DependencyAnalysisResult = {
  repository: string;
  defaultBranch: string;
  sourceFileCount: number;
  analyzedFileCount: number;
  skippedFileCount: number;
  truncated: boolean;
  dependencyCount: number;
  unresolvedImportCount: number;
  analyzedFiles: string[];
  dependencies: Dependency[];
  unresolvedImports: UnresolvedImport[];
  note: string;
};

type RiskFinding = {
  file: string;
  line: number;
  severity: "HIGH" | "MEDIUM" | "LOW";
  rule: string;
  explanation: string;
  recommendation: string;
};

type CodeAnalysisResult = {
  repository: string;
  defaultBranch: string;
  analyzedFileCount: number;
  skippedFileCount: number;
  truncated: boolean;
  findingCount: number;
  findings: RiskFinding[];
  analyzedFiles: string[];
  note: string;
};

type ApiErrorResponse = {
  status?: number;
  error?: string;
  message?: string;
  messages?: Record<string, string>;
};

type FormMessageType = "success" | "error";
type StructureFilter = "all" | "files" | "folders";

function isRecord(
  value: unknown,
): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value)
  );
}

function isNonNegativeNumber(
  value: unknown,
): value is number {
  return (
    typeof value === "number" &&
    Number.isFinite(value) &&
    value >= 0
  );
}

function isStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.every((item) => typeof item === "string")
  );
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function isValidGitHubRepositoryUrl(url: string): boolean {
  try {
    const parsed = new URL(url.trim());

    if (
      parsed.protocol !== "https:" ||
      parsed.hostname.toLowerCase() !== "github.com" ||
      parsed.username ||
      parsed.password ||
      parsed.port ||
      parsed.search ||
      parsed.hash
    ) {
      return false;
    }

    const segments = parsed.pathname.split("/").filter(Boolean);

    if (
      segments.length !== 2 ||
      !/^[A-Za-z0-9-]+$/.test(segments[0]) ||
      !GITHUB_REPOSITORY_PATTERN.test(segments[1]) ||
      segments[0] === "." ||
      segments[0] === ".." ||
      segments[1] === "." ||
      segments[1] === ".."
    ) {
      return false;
    }

    return true;
  } catch {
    return false;
  }
}

function normalizeRepositoryUrl(url: string): string {
  try {
    const parsed = new URL(url.trim());
    const segments = parsed.pathname.split("/").filter(Boolean);

    return `https://github.com/${segments[0].toLowerCase()}/${segments[1].toLowerCase()}`;
  } catch {
    return url.trim().replace(/\/+$/, "").toLowerCase();
  }
}

function getGitHubOwnerAndRepo(url: string): {
  owner: string;
  repo: string;
} {
  if (!isValidGitHubRepositoryUrl(url)) {
    throw new Error(
      "Please enter a valid GitHub repository URL.",
    );
  }

  const parsed = new URL(url.trim());
  const segments = parsed.pathname.split("/").filter(Boolean);

  return {
    owner: segments[0],
    repo: segments[1],
  };
}

function isApiHealth(value: unknown): value is ApiHealth {
  return (
    isRecord(value) &&
    typeof value.service === "string" &&
    typeof value.status === "string"
  );
}

function isProject(value: unknown): value is Project {
  return (
    isRecord(value) &&
    typeof value.id === "number" &&
    Number.isInteger(value.id) &&
    typeof value.name === "string" &&
    (typeof value.description === "string" ||
      value.description === null) &&
    typeof value.repositoryUrl === "string" &&
    typeof value.createdAt === "string"
  );
}

function isRepositoryAnalysis(
  value: unknown,
): value is RepositoryAnalysis {
  if (!isRecord(value)) {
    return false;
  }

  return (
    typeof value.name === "string" &&
    typeof value.fullName === "string" &&
    (typeof value.description === "string" ||
      value.description === null) &&
    typeof value.htmlUrl === "string" &&
    typeof value.defaultBranch === "string" &&
    isNonNegativeNumber(value.stars) &&
    isNonNegativeNumber(value.forks) &&
    isNonNegativeNumber(value.openIssues) &&
    (typeof value.primaryLanguage === "string" ||
      value.primaryLanguage === null) &&
    isRecord(value.languages) &&
    Object.values(value.languages).every(isNonNegativeNumber)
  );
}

function isRiskFinding(value: unknown): value is RiskFinding {
  return (
    isRecord(value) &&
    typeof value.file === "string" &&
    typeof value.line === "number" &&
    Number.isInteger(value.line) &&
    value.line >= 1 &&
    (value.severity === "HIGH" ||
      value.severity === "MEDIUM" ||
      value.severity === "LOW") &&
    typeof value.rule === "string" &&
    typeof value.explanation === "string" &&
    typeof value.recommendation === "string"
  );
}

function isCodeAnalysisResult(
  value: unknown,
): value is CodeAnalysisResult {
  return (
    isRecord(value) &&
    typeof value.repository === "string" &&
    typeof value.defaultBranch === "string" &&
    isNonNegativeNumber(value.analyzedFileCount) &&
    isNonNegativeNumber(value.skippedFileCount) &&
    typeof value.truncated === "boolean" &&
    isNonNegativeNumber(value.findingCount) &&
    Array.isArray(value.findings) &&
    value.findings.every(isRiskFinding) &&
    isStringArray(value.analyzedFiles) &&
    typeof value.note === "string"
  );
}

function isRepositoryStructure(
  value: unknown,
): value is RepositoryStructure {
  if (!isRecord(value) || !isRecord(value.summary)) {
    return false;
  }

  const summary = value.summary;

  const validSummary =
    isStringArray(summary.topLevelFolders) &&
    isStringArray(summary.importantFiles) &&
    isStringArray(summary.detectedTechnologies) &&
    isStringArray(summary.detectedAreas) &&
    typeof summary.explanation === "string";

  const validEntries =
    Array.isArray(value.entries) &&
    value.entries.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.path === "string" &&
        (entry.type === "blob" || entry.type === "tree") &&
        (entry.size === null ||
          isNonNegativeNumber(entry.size)),
    );

  return (
    typeof value.repository === "string" &&
    typeof value.defaultBranch === "string" &&
    isNonNegativeNumber(value.totalEntries) &&
    isNonNegativeNumber(value.fileCount) &&
    isNonNegativeNumber(value.directoryCount) &&
    typeof value.truncated === "boolean" &&
    validSummary &&
    validEntries
  );
}

function isDependencyAnalysisResult(
  value: unknown,
): value is DependencyAnalysisResult {
  if (!isRecord(value)) {
    return false;
  }

  const validDependencies =
    Array.isArray(value.dependencies) &&
    value.dependencies.every(
      (item) =>
        isRecord(item) &&
        typeof item.source === "string" &&
        typeof item.import === "string" &&
        (item.kind === "internal" || item.kind === "external") &&
        (item.target === undefined ||
          typeof item.target === "string"),
    );

  const validUnresolvedImports =
    Array.isArray(value.unresolvedImports) &&
    value.unresolvedImports.every(
      (item) =>
        isRecord(item) &&
        typeof item.source === "string" &&
        typeof item.import === "string",
    );

  return (
    typeof value.repository === "string" &&
    typeof value.defaultBranch === "string" &&
    isNonNegativeNumber(value.sourceFileCount) &&
    isNonNegativeNumber(value.analyzedFileCount) &&
    isNonNegativeNumber(value.skippedFileCount) &&
    typeof value.truncated === "boolean" &&
    isNonNegativeNumber(value.dependencyCount) &&
    isNonNegativeNumber(value.unresolvedImportCount) &&
    isStringArray(value.analyzedFiles) &&
    validDependencies &&
    validUnresolvedImports &&
    typeof value.note === "string"
  );
}

function readStoredValue<T>(
  key: string,
  fallback: T,
  isValid: (value: unknown) => value is T,
): T {
  try {
    const stored = window.localStorage.getItem(key);

    if (stored === null) {
      return fallback;
    }

    const parsed: unknown = JSON.parse(stored);

    return isValid(parsed) ? parsed : fallback;
  } catch {
    return fallback;
  }
}

async function readResponse(
  response: Response,
): Promise<unknown> {
  const text = await response.text();

  if (!text.trim()) {
    return null;
  }

  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function getErrorMessage(
  data: unknown,
  status: number,
): string {
  if (isRecord(data)) {
    const apiError = data as ApiErrorResponse;

    if (isRecord(apiError.messages)) {
      const messages = Object.values(apiError.messages).filter(
        (item): item is string =>
          typeof item === "string" && item.trim().length > 0,
      );

      if (messages.length > 0) {
        return messages.join(" ");
      }
    }

    if (
      typeof apiError.message === "string" &&
      apiError.message.trim()
    ) {
      return apiError.message;
    }

    if (
      typeof apiError.error === "string" &&
      apiError.error.trim()
    ) {
      return apiError.error;
    }
  }

  if (typeof data === "string" && data.trim()) {
    return data.slice(0, 500);
  }

  if (status === 400) {
    return "Invalid request. Check the submitted information.";
  }

  if (status === 401 || status === 403) {
    return "Access denied. Check the API permissions and GitHub access limits.";
  }

  if (status === 404) {
    return "The requested resource was not found. Verify the endpoint and resource ID.";
  }

  if (status === 405) {
    return "This HTTP method is not supported by the backend endpoint.";
  }

  if (status === 409) {
    return "This resource already exists or conflicts with existing data.";
  }

  if (status === 429) {
    return "Too many requests. Please wait and try again.";
  }

  if (status >= 500) {
    return "The backend encountered an error. Check the backend logs and try again.";
  }

  return `Request failed (HTTP ${status}). Please try again.`;
}

async function apiRequest(
  path: string,
  init?: RequestInit,
): Promise<unknown> {
  let response: Response;

  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init?.body
          ? { "Content-Type": "application/json" }
          : {}),
        ...init?.headers,
      },
    });
  } catch {
    throw new Error(
      "Could not reach the TraceMind API. Check your internet connection, backend availability, and CORS configuration.",
    );
  }

  const data = await readResponse(response);

  if (!response.ok) {
    throw new Error(getErrorMessage(data, response.status));
  }

  return data;
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "Unknown size";
  }

  if (bytes >= 1_000_000) {
    return `${(bytes / 1_000_000).toFixed(2)} MB`;
  }

  if (bytes >= 1_000) {
    return `${(bytes / 1_000).toFixed(1)} KB`;
  }

  return `${bytes} bytes`;
}

function getDateLabel(date: string): string {
  if (!date || Number.isNaN(Date.parse(date))) {
    return "Date unavailable";
  }

  return new Date(date).toLocaleDateString();
}

type ImpactedFile = {
  path: string;
  depth: number;
};

function findImpactedFiles(
  dependencies: Dependency[],
  changedFile: string,
): ImpactedFile[] {
  const dependentsByTarget = new Map<string, Set<string>>();

  for (const dependency of dependencies) {
    if (dependency.kind !== "internal" || !dependency.target) {
      continue;
    }

    const sources =
      dependentsByTarget.get(dependency.target) ?? new Set<string>();

    sources.add(dependency.source);
    dependentsByTarget.set(dependency.target, sources);
  }

  const visited = new Set<string>([changedFile]);
  const queue: ImpactedFile[] = [{ path: changedFile, depth: 0 }];
  const impacted: ImpactedFile[] = [];

  for (let head = 0; head < queue.length; head += 1) {
    const current = queue[head];

    for (const dependent of dependentsByTarget.get(current.path) ?? []) {
      if (visited.has(dependent)) {
        continue;
      }

      visited.add(dependent);

      const depth = current.depth + 1;
      const impactedFile = { path: dependent, depth };

      impacted.push(impactedFile);
      queue.push(impactedFile);
    }
  }

  return impacted.sort(
    (a, b) => a.depth - b.depth || a.path.localeCompare(b.path),
  );
}

function App() {
  const [health, setHealth] = useState<ApiHealth | null>(null);
  const [healthError, setHealthError] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const [dependencyAnalysis, setDependencyAnalysis] =
    useState<DependencyAnalysisResult | null>(null);
  const [selectedImpactFile, setSelectedImpactFile] = useState("");
  const [dependencyLoading, setDependencyLoading] = useState(false);
  const [dependencyError, setDependencyError] = useState("");

  const [message, setMessage] = useState("");
  const [messageType, setMessageType] =
    useState<FormMessageType>("success");

  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [deleteSuccess, setDeleteSuccess] = useState("");

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [repositoryUrl, setRepositoryUrl] = useState("");

  const [analysisUrl, setAnalysisUrl] = useState(() =>
    readStoredValue("tracemind.analysisUrl", "", isString),
  );

  const [analysis, setAnalysis] =
    useState<RepositoryAnalysis | null>(() =>
      readStoredValue(
        "tracemind.analysis",
        null,
        (value): value is RepositoryAnalysis | null =>
          value === null || isRepositoryAnalysis(value),
      ),
    );

  const [structureUrl, setStructureUrl] = useState(() =>
    readStoredValue("tracemind.structureUrl", "", isString),
  );

  const [structure, setStructure] =
    useState<RepositoryStructure | null>(() =>
      readStoredValue(
        "tracemind.structure",
        null,
        (value): value is RepositoryStructure | null =>
          value === null || isRepositoryStructure(value),
      ),
    );

  const [riskAnalysis, setRiskAnalysis] =
    useState<CodeAnalysisResult | null>(null);
  const [riskAnalysisLoading, setRiskAnalysisLoading] = useState(false);
  const [riskAnalysisError, setRiskAnalysisError] = useState("");

  const [analyzing, setAnalyzing] = useState(false);
  const [analysisError, setAnalysisError] = useState("");
  const [analyzingStructure, setAnalyzingStructure] = useState(false);
  const [structureError, setStructureError] = useState("");
  const [structureFilter, setStructureFilter] =
    useState<StructureFilter>("all");
  const [showAllStructureEntries, setShowAllStructureEntries] =
    useState(false);

  useEffect(() => {
    const save = (key: string, value: unknown) => {
      try {
        if (value === null || value === "") {
          window.localStorage.removeItem(key);
        } else {
          window.localStorage.setItem(key, JSON.stringify(value));
        }
      } catch {
        // Local storage may be disabled or full.
      }
    };

    save("tracemind.analysisUrl", analysisUrl);
    save("tracemind.analysis", analysis);
    save("tracemind.structureUrl", structureUrl);
    save("tracemind.structure", structure);
  }, [analysisUrl, analysis, structureUrl, structure]);

  const loadProjects = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const data = await apiRequest("/projects");

      if (!Array.isArray(data) || !data.every(isProject)) {
        throw new Error(
          "The server returned an invalid project list.",
        );
      }

      setProjects(data);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Could not load repositories.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  const checkHealth = useCallback(async () => {
    try {
      const data = await apiRequest("/health");

      if (!isApiHealth(data)) {
        throw new Error(
          "The backend returned an invalid health response.",
        );
      }

      setHealth(data);
      setHealthError("");
    } catch (err) {
      setHealth(null);
      setHealthError(
        err instanceof Error ? err.message : "Backend unavailable",
      );
    }
  }, []);

  useEffect(() => {
    let active = true;

    const runHealthCheck = async () => {
      try {
        const data = await apiRequest("/health");

        if (!isApiHealth(data)) {
          throw new Error("Invalid health response.");
        }

        if (active) {
          setHealth(data);
          setHealthError("");
        }
      } catch (err) {
        if (active) {
          setHealth(null);
          setHealthError(
            err instanceof Error
              ? err.message
              : "Backend unavailable",
          );
        }
      }
    };

    void runHealthCheck();
    void loadProjects();

    return () => {
      active = false;
    };
  }, [loadProjects]);

  function clearFormMessage() {
    setMessage("");
  }

  async function handleAnalyzeDependencies() {
    setDependencyError("");

    const url = analysisUrl.trim();

    if (!isValidGitHubRepositoryUrl(url)) {
      setDependencyError(
        "Enter a valid public GitHub repository URL in the repository analysis form first.",
      );
      return;
    }

    let owner: string;
    let repo: string;

    try {
      ({ owner, repo } = getGitHubOwnerAndRepo(url));
    } catch {
      setDependencyError("Invalid GitHub repository URL.");
      return;
    }

    setDependencyLoading(true);

    try {
      const data = await apiRequest(
        `/dependencies/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`,
      );

      if (!isDependencyAnalysisResult(data)) {
        throw new Error(
          "The backend returned an unexpected dependency analysis response.",
        );
      }

      setDependencyAnalysis(data);
      setSelectedImpactFile(data.analyzedFiles[0] ?? "");
      setDependencyError("");
    } catch (err) {
      setDependencyError(
        err instanceof Error
          ? err.message
          : "Dependency analysis failed. Please try again.",
      );
    } finally {
      setDependencyLoading(false);
    }
  }

  async function handleAnalyzeRepository(
    event: FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();
    setAnalysisError("");

    const url = analysisUrl.trim();

    if (!isValidGitHubRepositoryUrl(url)) {
      setAnalysisError(
        "Enter a valid URL, such as https://github.com/Aditya957258/TraceMind",
      );
      return;
    }

    let owner: string;
    let repo: string;

    try {
      ({ owner, repo } = getGitHubOwnerAndRepo(url));
    } catch {
      setAnalysisError("Invalid GitHub repository URL.");
      return;
    }

    setAnalyzing(true);

    try {
      const data = await apiRequest(
        `/analysis/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`,
      );

      if (!isRepositoryAnalysis(data)) {
        throw new Error(
          "Unexpected repository analysis response from the backend.",
        );
      }

      setAnalysisUrl(url);
      setAnalysis(data);
      setStructureUrl(url);
      setAnalysisError("");
    } catch (err) {
      setAnalysisError(
        err instanceof Error
          ? err.message
          : "Repository analysis failed.",
      );
    } finally {
      setAnalyzing(false);
    }
  }

  async function handleRunRiskAnalysis() {
    setRiskAnalysisError("");

    const url = analysisUrl.trim();

    if (!isValidGitHubRepositoryUrl(url)) {
      setRiskAnalysisError(
        "Enter a valid public GitHub repository URL in the repository analysis form first.",
      );
      return;
    }

    let owner: string;
    let repo: string;

    try {
      ({ owner, repo } = getGitHubOwnerAndRepo(url));
    } catch {
      setRiskAnalysisError("Invalid GitHub repository URL.");
      return;
    }

    setRiskAnalysisLoading(true);

    try {
      const data = await apiRequest(
        `/code-analysis/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`,
      );

      if (!isCodeAnalysisResult(data)) {
        throw new Error(
          "The backend returned an unexpected risk analysis response.",
        );
      }

      setRiskAnalysis(data);
    } catch (err) {
      setRiskAnalysisError(
        err instanceof Error
          ? err.message
          : "Risk analysis failed. Please try again.",
      );
    } finally {
      setRiskAnalysisLoading(false);
    }
  }

  async function handleAnalyzeStructure(
    event: FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();
    setStructureError("");
    setShowAllStructureEntries(false);
    setStructureFilter("all");

    const url = structureUrl.trim();

    if (!isValidGitHubRepositoryUrl(url)) {
      setStructureError(
        "Enter a valid GitHub repository URL, such as https://github.com/Aditya957258/TraceMind",
      );
      return;
    }

    let owner: string;
    let repo: string;

    try {
      ({ owner, repo } = getGitHubOwnerAndRepo(url));
    } catch {
      setStructureError("Invalid GitHub repository URL.");
      return;
    }

    setAnalyzingStructure(true);

    try {
      const data = await apiRequest(
        `/structure/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`,
      );

      if (!isRepositoryStructure(data)) {
        throw new Error(
          "Unexpected repository structure response from the backend.",
        );
      }

      setStructureUrl(url);
      setStructure(data);
      setStructureError("");
    } catch (err) {
      setStructureError(
        err instanceof Error
          ? err.message
          : "Repository structure analysis failed.",
      );
    } finally {
      setAnalyzingStructure(false);
    }
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
      setMessage("Enter a valid HTTPS GitHub repository URL.");
      setMessageType("error");
      return;
    }

    if (trimmedDescription.length > 2000) {
      setMessage("Description cannot exceed 2000 characters.");
      setMessageType("error");
      return;
    }

    const normalizedUrl = normalizeRepositoryUrl(trimmedUrl);

    if (
      projects.some(
        (project) =>
          normalizeRepositoryUrl(project.repositoryUrl) === normalizedUrl,
      )
    ) {
      setMessage("This repository is already in your workspace.");
      setMessageType("error");
      return;
    }

    setSubmitting(true);
    setMessageType("success");

    try {
      const data = await apiRequest("/projects", {
        method: "POST",
        body: JSON.stringify({
          name: trimmedName,
          description: trimmedDescription || null,
          repositoryUrl: trimmedUrl,
        }),
      });

      if (!isProject(data)) {
        throw new Error(
          "The server returned an unexpected response. Refresh the workspace to check whether the repository was saved.",
        );
      }

      setProjects((current) => {
        const exists = current.some(
          (project) =>
            project.id === data.id ||
            normalizeRepositoryUrl(project.repositoryUrl) ===
              normalizeRepositoryUrl(data.repositoryUrl),
        );

        return exists ? current : [data, ...current];
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
          : "Could not save the repository.",
      );
      setMessageType("error");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeleteProject(project: Project) {
    if (deletingId !== null) {
      return;
    }

    const confirmed = window.confirm(
      `Are you sure you want to delete "${project.name}"? This action cannot be undone.`,
    );

    if (!confirmed) {
      return;
    }

    setDeletingId(project.id);
    setDeleteError("");
    setDeleteSuccess("");

    try {
      await apiRequest(
        `/projects/${encodeURIComponent(String(project.id))}`,
        { method: "DELETE" },
      );

      setProjects((current) =>
        current.filter((item) => item.id !== project.id),
      );

      setDeleteSuccess(
        `"${project.name}" was deleted successfully.`,
      );
    } catch (err) {
      setDeleteError(
        err instanceof Error
          ? err.message
          : "Could not delete the repository.",
      );

      await loadProjects();
    } finally {
      setDeletingId(null);
    }
  }

  const filteredStructureEntries = structure
    ? structure.entries.filter((entry) => {
        if (structureFilter === "files") {
          return entry.type === "blob";
        }

        if (structureFilter === "folders") {
          return entry.type === "tree";
        }

        return true;
      })
    : [];

  const visibleStructureEntries = showAllStructureEntries
    ? filteredStructureEntries
    : filteredStructureEntries.slice(0, 30);

  const languageEntries = analysis
    ? Object.entries(analysis.languages)
        .filter(([, bytes]) => isNonNegativeNumber(bytes))
        .sort((a, b) => b[1] - a[1])
    : [];

  const totalLanguageBytes = languageEntries.reduce(
    (sum, [, bytes]) => sum + bytes,
    0,
  );

  const internalDependencies =
    dependencyAnalysis?.dependencies.filter(
      (dependency) => dependency.kind === "internal",
    ) ?? [];

  const externalDependencies =
    dependencyAnalysis?.dependencies.filter(
      (dependency) => dependency.kind === "external",
    ) ?? [];

  const impactedFiles = selectedImpactFile
    ? findImpactedFiles(
        dependencyAnalysis?.dependencies ?? [],
        selectedImpactFile,
      )
    : [];

  const directImpactCount = impactedFiles.filter(
    (file) => file.depth === 1,
  ).length;

  const transitiveImpactCount = impactedFiles.filter(
    (file) => file.depth > 1,
  ).length;

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
            <span className="soon-tag">BETA</span>
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
          <a className="nav-item" href="#repository-analysis">
            <span>⌕</span> Analyze repository
          </a>
          <a className="nav-item" href="#repository-structure">
            <span>▤</span> Repository structure
          </a>
          <a
            className="nav-item"
            href="#dependency-analysis"
            onClick={(event) => {
              event.preventDefault();
              const section = document.getElementById("dependency-analysis");
              if (section) {
                section.scrollIntoView({ behavior: "smooth", block: "start" });
                window.history.replaceState(null, "", "#dependency-analysis");
              }
            }}
          >
            <span>⑂</span> Dependency analysis
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
              {health
                ? "API connected"
                : healthError || "Connecting"}
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
              <div className="stat-value stat-word">
                {analysis ? "Analyzed" : "Ready"}
              </div>
              <div className="stat-bottom">
                <strong>Repository analysis</strong>
                <span>
                  {analysis ? analysis.fullName : "GitHub metadata"}
                </span>
              </div>
            </article>
          </section>

          <section className="insights-panel" id="insights">
            <div className="insights-glow" />
            <div className="insights-content">
              <div className="insights-label">
                <span>✳</span> TRACEMIND INTELLIGENCE
              </div>
              <h2>Your code has a story.</h2>
              <p>
                Explore public repository metadata, programming language
                statistics, files, folders, and detected technologies.
                These features establish a foundation for deeper code
                understanding.
              </p>
              <div className="insights-features">
                <span><i>✓</i> Repository metadata</span>
                <span><i>✓</i> Language statistics</span>
                <span><i>✓</i> File and folder analysis</span>
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
            <span className="preview-badge">
              REPOSITORY INTELLIGENCE
            </span>
          </section>

          <section
            className="risk-analysis-section"
            aria-labelledby="risk-analysis-title"
          >
            <div className="risk-analysis-heading">
              <div>
                <div className="eyebrow">STATIC CODE REVIEW</div>
                <h2 id="risk-analysis-title">Risk insights</h2>
                <p>
                  Scan supported source files for suspicious patterns and
                  review evidence-backed recommendations.
                </p>
              </div>
              <button
                type="button"
                className="primary-button"
                onClick={() => void handleRunRiskAnalysis()}
                disabled={riskAnalysisLoading || !analysisUrl.trim()}
              >
                {riskAnalysisLoading
                  ? "Scanning source files…"
                  : "Run risk analysis →"}
              </button>
            </div>

            {!analysisUrl.trim() && (
              <p className="risk-analysis-hint">
                Enter a public GitHub repository URL in “Analyze a
                repository” below, then return here to run the scan.
              </p>
            )}

            {riskAnalysisError && (
              <div className="notice-error" role="alert">
                {riskAnalysisError}
              </div>
            )}

            {riskAnalysis && (
              <>
                <div className="risk-summary-grid">
                  <div className="risk-summary-card">
                    <span>Files scanned</span>
                    <strong>{riskAnalysis.analyzedFileCount}</strong>
                  </div>
                  <div className="risk-summary-card">
                    <span>Total findings</span>
                    <strong>{riskAnalysis.findingCount}</strong>
                  </div>
                  <div className="risk-summary-card">
                    <span>High severity</span>
                    <strong>
                      {riskAnalysis.findings.filter(
                        (finding) => finding.severity === "HIGH",
                      ).length}
                    </strong>
                  </div>
                </div>

                <div className="risk-result-meta">
                  <span>{riskAnalysis.repository}</span>
                  <span>Branch: {riskAnalysis.defaultBranch}</span>
                </div>

                {(riskAnalysis.skippedFileCount > 0 ||
                  riskAnalysis.truncated) && (
                  <p className="risk-analysis-hint">
                    Coverage note: some files may not have been scanned.
                    Skipped files: {riskAnalysis.skippedFileCount}.
                    {riskAnalysis.truncated
                      ? " GitHub reported a truncated repository tree."
                      : ""}
                  </p>
                )}

                {riskAnalysis.findings.length === 0 ? (
                  <div className="empty-panel">
                    <h3>No heuristic findings detected</h3>
                    <p>
                      This scan did not match any of its current rules.
                      It does not prove the repository is vulnerability-free.
                    </p>
                  </div>
                ) : (
                  <div className="risk-findings-list">
                    {riskAnalysis.findings.map((finding, index) => (
                      <article
                        className="risk-finding-card"
                        key={`${finding.file}:${finding.line}:${finding.rule}:${index}`}
                      >
                        <div className="risk-finding-top">
                          <span
                            className={`risk-severity severity-${finding.severity.toLowerCase()}`}
                          >
                            {finding.severity}
                          </span>
                          <span className="risk-finding-location">
                            {finding.file}:{finding.line}
                          </span>
                        </div>
                        <h3>{finding.rule}</h3>
                        <p>{finding.explanation}</p>
                        <div className="risk-recommendation">
                          <strong>Recommended action</strong>
                          <p>{finding.recommendation}</p>
                        </div>
                      </article>
                    ))}
                  </div>
                )}

                <p className="risk-analysis-disclaimer">
                  {riskAnalysis.note} Findings are heuristic signals,
                  not confirmed vulnerabilities. Review the source context
                  before taking action.
                </p>
              </>
            )}
          </section>

          <section className="analysis-section" id="repository-analysis">
            <div className="section-heading">
              <div>
                <div className="eyebrow">GITHUB INTELLIGENCE</div>
                <h2>Analyze a repository</h2>
                <p>
                  Inspect public GitHub repository metadata and programming
                  language statistics.
                </p>
              </div>
            </div>

            <form
              className="repository-form analysis-form"
              onSubmit={handleAnalyzeRepository}
              noValidate
            >
              <label htmlFor="analysis-url">
                GitHub repository URL <span>*</span>
              </label>
              <input
                id="analysis-url"
                type="url"
                inputMode="url"
                value={analysisUrl}
                onChange={(event) => {
                  setAnalysisUrl(event.target.value);
                  setAnalysisError("");
                }}
                placeholder="https://github.com/Aditya957258/TraceMind"
                maxLength={2048}
                autoComplete="url"
                spellCheck={false}
                required
              />
              <button
                className="primary-button form-submit"
                type="submit"
                disabled={analyzing}
              >
                {analyzing
                  ? "Analyzing repository…"
                  : "Analyze repository"}
                {!analyzing && <span>→</span>}
              </button>
              {analysisError && (
                <p className="form-message error-message" role="alert">
                  {analysisError}
                </p>
              )}
            </form>

            {analysis && (
              <article className="analysis-result">
                <div className="analysis-result-header">
                  <div>
                    <div className="eyebrow">ANALYSIS RESULT</div>
                    <h3>{analysis.fullName}</h3>
                    <p>
                      {analysis.description ||
                        "This repository has no description."}
                    </p>
                  </div>
                  <a
                    className="secondary-button"
                    href={analysis.htmlUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    Open on GitHub ↗
                  </a>
                </div>

                <div className="analysis-stats-grid">
                  <div className="analysis-stat">
                    <span>★</span>
                    <small>Stars</small>
                    <strong>{analysis.stars.toLocaleString()}</strong>
                  </div>
                  <div className="analysis-stat">
                    <span>⑂</span>
                    <small>Forks</small>
                    <strong>{analysis.forks.toLocaleString()}</strong>
                  </div>
                  <div className="analysis-stat">
                    <span>◎</span>
                    <small>Open issues</small>
                    <strong>{analysis.openIssues.toLocaleString()}</strong>
                  </div>
                  <div className="analysis-stat">
                    <span>{"</>"}</span>
                    <small>Primary language</small>
                    <strong>
                      {analysis.primaryLanguage || "Not specified"}
                    </strong>
                  </div>
                </div>

                <div className="analysis-details">
                  <p>
                    <strong>Default branch:</strong>{" "}
                    {analysis.defaultBranch || "Not available"}
                  </p>
                </div>

                <div className="language-section">
                  <h4>Language breakdown</h4>
                  {languageEntries.length === 0 ||
                  totalLanguageBytes === 0 ? (
                    <p>No measurable language statistics are available.</p>
                  ) : (
                    <>
                      <div
                        className="language-bar"
                        role="img"
                        aria-label="Programming language distribution"
                      >
                        {languageEntries.map(([language, bytes]) => (
                          <span
                            key={language}
                            className="language-bar-segment"
                            title={`${language}: ${(
                              (bytes / totalLanguageBytes) *
                              100
                            ).toFixed(1)}%`}
                            style={{
                              width: `${
                                (bytes / totalLanguageBytes) * 100
                              }%`,
                            }}
                          />
                        ))}
                      </div>
                      <div className="language-list">
                        {languageEntries.map(([language, bytes]) => (
                          <div className="language-item" key={language}>
                            <span className="language-name">
                              <span className="language-dot" />
                              {language}
                            </span>
                            <span>
                              {(
                                (bytes / totalLanguageBytes) *
                                100
                              ).toFixed(1)}
                              %
                            </span>
                            <small>{formatBytes(bytes)}</small>
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </article>
            )}
          </section>

          <section className="analysis-section" id="repository-structure">
            <div className="section-heading">
              <div>
                <div className="eyebrow">CODEBASE EXPLORER</div>
                <h2>Repository structure</h2>
                <p>
                  Explore the repository tree, important files, top-level
                  folders, and technologies detected by TraceMind.
                </p>
              </div>
            </div>

            <form
              className="repository-form analysis-form"
              onSubmit={handleAnalyzeStructure}
              noValidate
            >
              <label htmlFor="structure-url">
                GitHub repository URL <span>*</span>
              </label>
              <input
                id="structure-url"
                type="url"
                inputMode="url"
                value={structureUrl}
                onChange={(event) => {
                  setStructureUrl(event.target.value);
                  setStructureError("");
                }}
                placeholder="https://github.com/Aditya957258/TraceMind"
                maxLength={2048}
                autoComplete="url"
                spellCheck={false}
                required
              />
              <button
                className="primary-button form-submit"
                type="submit"
                disabled={analyzingStructure}
              >
                {analyzingStructure
                  ? "Inspecting repository…"
                  : "Analyze structure"}
                {!analyzingStructure && <span>→</span>}
              </button>

              {analysisUrl.trim() && (
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => {
                    setStructureUrl(analysisUrl);
                    setStructureError("");
                  }}
                >
                  Use metadata analysis URL
                </button>
              )}

              {structureError && (
                <p className="form-message error-message" role="alert">
                  {structureError}
                </p>
              )}
            </form>

            {structure && (
              <article className="analysis-result">
                <div className="analysis-result-header">
                  <div>
                    <div className="eyebrow">
                      STRUCTURE ANALYSIS RESULT
                    </div>
                    <h3>{structure.repository}</h3>
                    <p>
                      Default branch:{" "}
                      <strong>
                        {structure.defaultBranch || "Not available"}
                      </strong>
                    </p>
                  </div>
                </div>

                <div className="analysis-stats-grid">
                  <div className="analysis-stat">
                    <span>▤</span>
                    <small>Total entries</small>
                    <strong>
                      {structure.totalEntries.toLocaleString()}
                    </strong>
                  </div>
                  <div className="analysis-stat">
                    <span>▧</span>
                    <small>Files</small>
                    <strong>{structure.fileCount.toLocaleString()}</strong>
                  </div>
                  <div className="analysis-stat">
                    <span>▣</span>
                    <small>Directories</small>
                    <strong>
                      {structure.directoryCount.toLocaleString()}
                    </strong>
                  </div>
                  <div className="analysis-stat">
                    <span>⌘</span>
                    <small>Branch</small>
                    <strong>{structure.defaultBranch || "Unknown"}</strong>
                  </div>
                </div>

                {structure.truncated && (
                  <div className="notice-error" role="status">
                    The repository tree was truncated by the GitHub API.
                    Some files or folders may be missing.
                  </div>
                )}

                <div className="language-section">
                  <h4>Repository summary</h4>
                  <p>
                    {structure.summary.explanation ||
                      "No additional explanation is available."}
                  </p>
                </div>

                <div className="language-section">
                  <h4>Top-level folders</h4>
                  {structure.summary.topLevelFolders.length ? (
                    <div className="insights-features">
                      {structure.summary.topLevelFolders.map((folder) => (
                        <span key={folder}>
                          <i>▣</i> {folder}
                        </span>
                      ))}
                    </div>
                  ) : (
                    <p>No top-level folders were detected.</p>
                  )}
                </div>

                <div className="language-section">
                  <h4>Detected technologies</h4>
                  {structure.summary.detectedTechnologies.length ? (
                    <div className="insights-features">
                      {structure.summary.detectedTechnologies.map(
                        (technology) => (
                          <span key={technology}>
                            <i>✓</i> {technology}
                          </span>
                        ),
                      )}
                    </div>
                  ) : (
                    <p>No technologies were detected.</p>
                  )}
                </div>

                <div className="language-section">
                  <h4>Detected project areas</h4>
                  {structure.summary.detectedAreas.length ? (
                    <div className="insights-features">
                      {structure.summary.detectedAreas.map((area) => (
                        <span key={area}>
                          <i>✓</i> {area}
                        </span>
                      ))}
                    </div>
                  ) : (
                    <p>No project areas were detected.</p>
                  )}
                </div>

                <div className="language-section">
                  <h4>Important files</h4>
                  {structure.summary.importantFiles.length ? (
                    <ul className="structure-important-files">
                      {structure.summary.importantFiles.map((file) => (
                        <li key={file}>
                          <code>{file}</code>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p>No important files were identified.</p>
                  )}
                </div>

                <div className="language-section">
                  <h4>File and folder explorer</h4>
                  <div className="structure-toolbar">
                    <label htmlFor="structure-filter">Show</label>
                    <select
                      id="structure-filter"
                      value={structureFilter}
                      onChange={(event) =>
                        setStructureFilter(
                          event.target.value as StructureFilter,
                        )
                      }
                    >
                      <option value="all">All entries</option>
                      <option value="files">Files only</option>
                      <option value="folders">Folders only</option>
                    </select>
                    <span className="structure-entry-count">
                      {filteredStructureEntries.length.toLocaleString()} entries
                    </span>
                  </div>

                  {visibleStructureEntries.length === 0 ? (
                    <p>No entries match this filter.</p>
                  ) : (
                    <div className="structure-tree">
                      {visibleStructureEntries.map((entry) => (
                        <div
                          className="structure-entry"
                          key={entry.path}
                        >
                          <span
                            className="structure-entry-icon"
                            aria-hidden="true"
                          >
                            {entry.type === "tree" ? "📁" : "📄"}
                          </span>
                          <code className="structure-entry-path">
                            {entry.path}
                          </code>
                          <span className="structure-entry-type">
                            {entry.type === "tree" ? "Folder" : "File"}
                          </span>
                          <small className="structure-entry-size">
                            {entry.size === null
                              ? "—"
                              : formatBytes(entry.size)}
                          </small>
                        </div>
                      ))}
                    </div>
                  )}

                  {filteredStructureEntries.length > 30 && (
                    <button
                      type="button"
                      className="secondary-button structure-toggle"
                      onClick={() =>
                        setShowAllStructureEntries((current) => !current)
                      }
                    >
                      {showAllStructureEntries
                        ? "Show fewer entries"
                        : `Show all ${filteredStructureEntries.length} entries`}
                    </button>
                  )}
                </div>
              </article>
            )}
          </section>

          <section
            className="analysis-section"
            id="dependency-analysis"
          >
            <div className="section-heading">
              <div>
                <div className="eyebrow">CODE RELATIONSHIPS</div>
                <h2>Dependency analysis</h2>
                <p>
                  Map detected source imports, identify internal file
                  dependencies, and review unresolved relative imports.
                </p>
              </div>
              <button
                type="button"
                className="primary-button"
                onClick={() => void handleAnalyzeDependencies()}
                disabled={
                  dependencyLoading || !analysisUrl.trim()
                }
              >
                {dependencyLoading
                  ? "Mapping dependencies…"
                  : "Map dependencies →"}
              </button>
            </div>

            {!analysisUrl.trim() && (
              <p className="risk-analysis-hint">
                Enter a public GitHub repository URL in “Analyze a
                repository” first.
              </p>
            )}

            {dependencyError && (
              <div className="notice-error" role="alert">
                {dependencyError}
              </div>
            )}

            {dependencyAnalysis && (
              <article className="analysis-result">
                <div className="analysis-result-header">
                  <div>
                    <div className="eyebrow">
                      DEPENDENCY ANALYSIS RESULT
                    </div>
                    <h3>{dependencyAnalysis.repository}</h3>
                    <p>
                      Default branch:{" "}
                      <strong>
                        {dependencyAnalysis.defaultBranch ||
                          "Not available"}
                      </strong>
                    </p>
                  </div>
                </div>

                <div className="analysis-stats-grid">
                  <div className="analysis-stat">
                    <span>▤</span>
                    <small>Source files</small>
                    <strong>
                      {dependencyAnalysis.sourceFileCount.toLocaleString()}
                    </strong>
                  </div>
                  <div className="analysis-stat">
                    <span>⑂</span>
                    <small>Dependencies</small>
                    <strong>
                      {dependencyAnalysis.dependencyCount.toLocaleString()}
                    </strong>
                  </div>
                  <div className="analysis-stat">
                    <span>↗</span>
                    <small>Internal imports</small>
                    <strong>
                      {internalDependencies.length.toLocaleString()}
                    </strong>
                  </div>
                  <div className="analysis-stat">
                    <span>!</span>
                    <small>Unresolved imports</small>
                    <strong>
                      {dependencyAnalysis.unresolvedImportCount.toLocaleString()}
                    </strong>
                  </div>
                </div>

                <div className="language-section">
                  <h4>Change impact analysis</h4>
                  <p>
                    Select a source file to find files that depend on it,
                    directly or through other files. These are potential
                    impacts, not guaranteed breakages.
                  </p>

                  <label htmlFor="impact-file-select">Changed file</label>
                  <select
                    id="impact-file-select"
                    value={selectedImpactFile}
                    onChange={(event) =>
                      setSelectedImpactFile(event.target.value)
                    }
                    disabled={dependencyAnalysis.analyzedFiles.length === 0}
                  >
                    {dependencyAnalysis.analyzedFiles.map((file) => (
                      <option key={file} value={file}>
                        {file}
                      </option>
                    ))}
                  </select>

                  {dependencyAnalysis.analyzedFiles.length === 0 ? (
                    <p>
                      No source files were analyzed, so change impact cannot
                      be calculated.
                    </p>
                  ) : selectedImpactFile ? (
                    <>
                      <div className="analysis-stats-grid">
                        <div className="analysis-stat">
                          <small>Direct dependents</small>
                          <strong>{directImpactCount}</strong>
                        </div>
                        <div className="analysis-stat">
                          <small>Transitive dependents</small>
                          <strong>{transitiveImpactCount}</strong>
                        </div>
                      </div>

                      {impactedFiles.length === 0 ? (
                        <p>
                          No dependents were found for this file in the
                          resolved dependency graph.
                        </p>
                      ) : (
                        <div className="structure-tree">
                          {impactedFiles.map((file) => (
                            <div className="structure-entry" key={file.path}>
                              <span
                                className="structure-entry-icon"
                                aria-hidden="true"
                              >
                                {file.depth === 1 ? "↗" : "↳"}
                              </span>

                              <div className="structure-entry-path">
                                <code>{file.path}</code>
                                <small>
                                  {file.depth === 1
                                    ? "Direct dependent"
                                    : `Transitive dependent · depth ${file.depth}`}
                                </small>
                              </div>

                              <span className="structure-entry-type">
                                {file.depth === 1 ? "Direct" : "Transitive"}
                              </span>
                            </div>
                          ))}
                        </div>
                      )}
                    </>
                  ) : null}
                </div>

                {(dependencyAnalysis.skippedFileCount > 0 ||
                  dependencyAnalysis.truncated) && (
                  <p className="risk-analysis-hint">
                    Coverage note: some source files may not have been
                    analyzed. Skipped files:{" "}
                    {dependencyAnalysis.skippedFileCount}.
                    {dependencyAnalysis.truncated
                      ? " GitHub reported a truncated repository tree."
                      : ""}
                  </p>
                )}

                <div className="language-section">
                  <h4>Internal dependencies</h4>
                  <p>
                    Imports that TraceMind resolved to another file
                    in this repository.
                  </p>

                  {internalDependencies.length === 0 ? (
                    <p>No internal dependencies were resolved.</p>
                  ) : (
                    <div className="structure-tree">
                      {internalDependencies.map((dependency, index) => (
                        <div
                          className="structure-entry"
                          key={`${dependency.source}:${dependency.import}:${index}`}
                        >
                          <span
                            className="structure-entry-icon"
                            aria-hidden="true"
                          >
                            ↗
                          </span>
                          <div className="structure-entry-path">
                            <code>{dependency.source}</code>
                            <small>
                              imports {dependency.import}
                            </small>
                          </div>
                          <span className="structure-entry-type">
                            {dependency.target ?? "Resolved"}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div className="language-section">
                  <h4>External imports</h4>
                  <p>
                    Package or module imports that were not resolved
                    to an internal source file.
                  </p>

                  {externalDependencies.length === 0 ? (
                    <p>No external imports were detected.</p>
                  ) : (
                    <div className="structure-tree">
                      {externalDependencies.map((dependency, index) => (
                        <div
                          className="structure-entry"
                          key={`${dependency.source}:${dependency.import}:${index}`}
                        >
                          <span
                            className="structure-entry-icon"
                            aria-hidden="true"
                          >
                            ↗
                          </span>
                          <div className="structure-entry-path">
                            <code>{dependency.source}</code>
                            <small>
                              imports {dependency.import}
                            </small>
                          </div>
                          <span className="structure-entry-type">
                            External
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div className="language-section">
                  <h4>Unresolved relative imports</h4>
                  <p>
                    These relative imports did not match a source file
                    using the resolver's supported path rules. Check
                    aliases, generated files, and extension conventions
                    before treating them as errors.
                  </p>

                  {dependencyAnalysis.unresolvedImports.length === 0 ? (
                    <p>No unresolved relative imports were reported.</p>
                  ) : (
                    <div className="structure-tree">
                      {dependencyAnalysis.unresolvedImports.map(
                        (dependency, index) => (
                          <div
                            className="structure-entry"
                            key={`${dependency.source}:${dependency.import}:${index}`}
                          >
                            <span
                              className="structure-entry-icon"
                              aria-hidden="true"
                            >
                              !
                            </span>
                            <div className="structure-entry-path">
                              <code>{dependency.source}</code>
                              <small>
                                imports {dependency.import}
                              </small>
                            </div>
                            <span className="structure-entry-type">
                              Unresolved
                            </span>
                          </div>
                        ),
                      )}
                    </div>
                  )}
                </div>

                <div className="language-section">
                  <h4>Analysis note</h4>
                  <p>{dependencyAnalysis.note}</p>
                  <p className="risk-analysis-disclaimer">
                    Dependency mapping uses static import patterns. It
                    does not guarantee complete compiler-level dependency
                    resolution and may not identify dynamic imports,
                    configured aliases, or every language construct.
                  </p>
                </div>
              </article>
            )}
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
                onClick={() => {
                  setDeleteError("");
                  setDeleteSuccess("");
                  void loadProjects();
                  void checkHealth();
                }}
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

            {deleteSuccess && (
              <p className="form-message success-message" role="status">
                {deleteSuccess}
              </p>
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
                  <article
                    className="repository-card"
                    key={project.id}
                  >
                    <div className="repository-card-header">
                      <div className="repository-symbol">{"</>"}</div>
                      <span className="repository-status">
                        <span className="status-dot is-online" />
                        CONNECTED
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
                      <span>{getDateLabel(project.createdAt)}</span>
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
                type="url"
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
              DEPLOYED API <span>•</span> V1.0
            </span>
          </footer>
        </div>
      </main>
    </div>
  );
}

export default App;
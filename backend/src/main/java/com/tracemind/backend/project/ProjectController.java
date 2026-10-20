package com.tracemind.backend.project;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/projects")
@CrossOrigin(origins = "http://localhost:5173")
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    // Get all projects
    @GetMapping
    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    // Create a new project
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project createProject(
            @Valid @RequestBody CreateProjectRequest request) {

        Project project = new Project(
                request.name().trim(),
                request.description() == null
                        ? null
                        : request.description().trim(),
                request.repositoryUrl().trim()
        );

        return projectRepository.save(project);
    }

    // Delete a project by ID
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable Long id) {

        if (!projectRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Project not found"
            );
        }

        projectRepository.deleteById(id);
    }

    // Request validation
    public record CreateProjectRequest(

            @NotBlank(message = "Project name is required")
            @Size(max = 255, message = "Project name cannot exceed 255 characters")
            String name,

            @Size(max = 2000, message = "Description cannot exceed 2000 characters")
            String description,

            @NotBlank(message = "Repository URL is required")
            @Size(max = 2048, message = "Repository URL cannot exceed 2048 characters")
            @Pattern(
                    regexp = "^https://github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+/?$",
                    message = "Enter a valid HTTPS GitHub repository URL"
            )
            String repositoryUrl

    ) {}
}
package com.tracemind.backend.project;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.support.GenericWebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerTest {

    private ProjectRepository projectRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);

        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectController(projectRepository))
                .setValidator(validator)
                .build();
    }

    @Test
    void getAllProjectsReturnsRepositoryProjects() throws Exception {
        Project project = new Project(
                "TraceMind",
                "Repository analysis platform",
                "https://github.com/Aditya957258/TraceMind"
        );

        when(projectRepository.findAll()).thenReturn(List.of(project));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("TraceMind"))
                .andExpect(jsonPath("$[0].description")
                        .value("Repository analysis platform"))
                .andExpect(jsonPath("$[0].repositoryUrl")
                        .value("https://github.com/Aditya957258/TraceMind"));
    }

    @Test
    void createProjectTrimsFieldsAndReturnsCreated() throws Exception {
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  TraceMind  ",
                                  "description": "  Analyze repositories  ",
                                  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("TraceMind"))
                .andExpect(jsonPath("$.description")
                        .value("Analyze repositories"))
                .andExpect(jsonPath("$.repositoryUrl")
                        .value("https://github.com/Aditya957258/TraceMind"));
    }

    @Test
    void createProjectAllowsNullDescription() throws Exception {
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "TraceMind",
                                  "description": null,
                                  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void createProjectRejectsBlankName() throws Exception {
        assertInvalidProject("""
                {
                  "name": "   ",
                  "description": "Description",
                  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
                }
                """);
    }

    @Test
    void createProjectRejectsMissingRepositoryUrl() throws Exception {
        assertInvalidProject("""
                {
                  "name": "TraceMind",
                  "description": "Description"
                }
                """);
    }

    @Test
    void createProjectRejectsInvalidRepositoryUrl() throws Exception {
        assertInvalidProject("""
                {
                  "name": "TraceMind",
                  "description": "Description",
                  "repositoryUrl": "http://github.com/Aditya957258/TraceMind"
                }
                """);
    }

    @Test
    void createProjectRejectsNameLongerThan255Characters()
            throws Exception {
        String longName = "a".repeat(256);

        assertInvalidProject("""
                {
                  "name": "%s",
                  "description": null,
                  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
                }
                """.formatted(longName));
    }

    @Test
    void createProjectRejectsDescriptionLongerThan2000Characters()
            throws Exception {
        String longDescription = "a".repeat(2001);

        assertInvalidProject("""
                {
                  "name": "TraceMind",
                  "description": "%s",
                  "repositoryUrl": "https://github.com/Aditya957258/TraceMind"
                }
                """.formatted(longDescription));
    }

    @Test
    void deleteProjectDeletesExistingProject() throws Exception {
        when(projectRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/projects/1"))
                .andExpect(status().isNoContent());

        verify(projectRepository).deleteById(1L);
    }

    @Test
    void deleteProjectReturnsNotFoundForMissingProject()
            throws Exception {
        when(projectRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/projects/999"))
                .andExpect(status().isNotFound());

        verify(projectRepository, never()).deleteById(999L);
    }

    private void assertInvalidProject(String requestBody)
            throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(projectRepository, never()).save(any(Project.class));
    }
}

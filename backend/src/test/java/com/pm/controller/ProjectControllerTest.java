package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.mapper.UserMapper;
import com.pm.service.ProjectService;
import com.pm.vo.ProjectDetailVO;
import com.pm.vo.ProjectListVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目接口切片测试：可见性 user 透传、创建的管理员校验与缩写规则、成员增删参数绑定。
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new ProjectController(projectService));
    }

    @AfterEach
    void tearDown() {
        ControllerTestSupport.clearCurrentUser();
    }

    @Test
    @DisplayName("列表：当前用户透传，分页默认 1/10")
    void pagePassesUser() throws Exception {
        Page<ProjectListVO> page = new Page<>(1, 10);
        ProjectListVO vo = new ProjectListVO();
        vo.setId(1L);
        vo.setCode("ACE");
        page.setRecords(List.of(vo));
        when(projectService.pageProjects(9L, null, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/projects").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].code").value("ACE"));

        verify(projectService).pageProjects(9L, null, 1, 10);
    }

    @Test
    @DisplayName("详情：当前用户与项目 id 透传")
    void detailPassesUser() throws Exception {
        ProjectDetailVO detail = new ProjectDetailVO();
        detail.setId(1L);
        detail.setCode("ACE");
        when(projectService.getProjectDetail(9L, 1L)).thenReturn(detail);

        mockMvc.perform(get("/api/projects/1").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.code").value("ACE"));
    }

    @Test
    @DisplayName("创建：缺少名称/缩写 → 400")
    void createValidation() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建：缩写非 2-6 位大写字母 → 400")
    void createInvalidCode() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目\",\"code\":\"ace\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建：管理员通过后以管理员 id 作为创建人")
    void createSuccess() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目\",\"code\":\"ACE\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(projectService).createProject(eq(9L), any());
    }

    @Test
    @DisplayName("编辑：名称为空 → 400")
    void updateValidation() throws Exception {
        mockMvc.perform(put("/api/projects/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("编辑成功：路径 id 与当前用户透传")
    void updateSuccess() throws Exception {
        mockMvc.perform(put("/api/projects/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新项目名\",\"description\":\"新描述\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(projectService).updateProject(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("添加成员成功：工种列表透传")
    void addMemberSuccess() throws Exception {
        mockMvc.perform(post("/api/projects/1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"crafts\":[\"FE\",\"QA\"]}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(projectService).addMember(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("添加成员：工种为空 → 400")
    void addMemberValidation() throws Exception {
        mockMvc.perform(post("/api/projects/1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"crafts\":[]}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("移除成员：带 craft 删单个工种，不带 craft 删全部")
    void removeMember() throws Exception {
        mockMvc.perform(delete("/api/projects/1/members/2")
                        .param("craft", "FE")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/api/projects/1/members/2")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(projectService).removeMember(9L, 1L, 2L, "FE");
        verify(projectService).removeMember(eq(9L), eq(1L), eq(2L), isNull());
    }
}

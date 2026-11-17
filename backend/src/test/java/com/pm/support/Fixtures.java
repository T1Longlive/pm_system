package com.pm.support;

import com.pm.entity.CalendarDay;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.entity.ScheduleCell;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 测试数据构造器：只负责「造实体」，不负责落库。
 *
 * <p>业务字段给出合法默认值，测试只需覆盖关心的字段；落库由测试自己调用对应 Mapper，
 * 这样单元测试可以复用同一套构造器而不依赖 Spring 上下文。
 */
public final class Fixtures {

    /** 初始密码 Pm@123456 的 BCrypt 哈希（与 init.sql 种子管理员一致） */
    public static final String DEFAULT_PASSWORD_HASH =
            "$2a$10$8Vlc7gKDCNIWgWuFn6sYr.QwKS0NJZn9vUnHlbaTxCWRakm0JfmZG";

    private Fixtures() {
    }

    public static User user(String username, String realName, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(DEFAULT_PASSWORD_HASH);
        user.setRealName(realName);
        user.setIsAdmin(admin ? 1 : 0);
        user.setStatus(1);
        user.setForceChangePwd(0);
        return user;
    }

    public static Project project(String code, Long creatorId) {
        Project project = new Project();
        project.setCode(code);
        project.setName("测试项目-" + code);
        project.setDescription("集成测试自动创建");
        project.setCreatorId(creatorId);
        return project;
    }

    public static ProjectMember member(Long projectId, Long userId, String craft) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setCraft(craft);
        return member;
    }

    public static Requirement requirement(Long projectId, String reqNo, String title, String status,
                                          Long plannerId, Long createdBy) {
        Requirement requirement = new Requirement();
        requirement.setProjectId(projectId);
        requirement.setReqNo(reqNo);
        requirement.setTitle(title);
        requirement.setDescription("<p>集成测试需求</p>");
        requirement.setPriority(2);
        requirement.setStatus(status);
        requirement.setPlannerId(plannerId);
        requirement.setCreatedBy(createdBy);
        return requirement;
    }

    public static RequirementParticipant participant(Long requirementId, Long userId, String craft) {
        RequirementParticipant participant = new RequirementParticipant();
        participant.setRequirementId(requirementId);
        participant.setUserId(userId);
        participant.setCraft(craft);
        return participant;
    }

    public static ScheduleMonth month(int year, int month, Long createdBy) {
        ScheduleMonth scheduleMonth = new ScheduleMonth();
        scheduleMonth.setYear(year);
        scheduleMonth.setMonth(month);
        scheduleMonth.setCreatedBy(createdBy);
        return scheduleMonth;
    }

    public static ScheduleRow row(Long monthId, Long requirementId, String craft, Long userId,
                                  int seq, String plannedEffort) {
        ScheduleRow row = new ScheduleRow();
        row.setMonthId(monthId);
        row.setRequirementId(requirementId);
        row.setCraft(craft);
        row.setUserId(userId);
        row.setSeq(seq);
        row.setPlannedEffort(plannedEffort == null ? null : new BigDecimal(plannedEffort));
        row.setStatus("TODO");
        row.setIsOverflow(0);
        return row;
    }

    public static ScheduleCell cell(Long rowId, LocalDate day, String value) {
        ScheduleCell cell = new ScheduleCell();
        cell.setRowId(rowId);
        cell.setDay(day);
        cell.setValue(new BigDecimal(value));
        return cell;
    }

    public static CalendarDay calendarDay(LocalDate day, String type) {
        CalendarDay calendarDay = new CalendarDay();
        calendarDay.setDay(day);
        calendarDay.setType(type);
        return calendarDay;
    }
}

package com.pm.vo;

import lombok.Data;

/**
 * 复制排期行到下月结果 VO（POST /api/schedule/rows/{rowId}/to-next-month）
 */
@Data
public class CopyRowResultVO {

    /** 下月排期表 ID（不存在时已自动创建） */
    private Long monthId;

    /** true=本次新插入；false=下月已存在同（需求,工种,负责人）行，未重复插入 */
    private boolean duplicated;
}

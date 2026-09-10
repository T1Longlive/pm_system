package com.pm.vo;

import lombok.Data;

/**
 * 整个需求添加到下月结果 VO（POST /api/schedule/rows/{rowId}/to-next-month）
 */
@Data
public class CopyRowResultVO {

    /** 下月排期表 ID（不存在时已自动创建） */
    private Long monthId;

    /** true=本次有新插入行；false=下月已存在该需求的全部排期组行，未重复插入 */
    private boolean duplicated;

    /** 本次实际新插入的行数（整个需求逐组复制，已存在的组跳过） */
    private Integer insertedCount;
}

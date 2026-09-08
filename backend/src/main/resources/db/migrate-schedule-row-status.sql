-- 排期行状态语义重构：DONE 完成态废弃，回退为 TODO
-- （已排/排期中/未排期三态由前端按 工期 vs 已排合计 派生；本脚本幂等可重复执行）
UPDATE schedule_row SET status = 'TODO' WHERE status = 'DONE';

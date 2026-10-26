# 历史 SQL 目录

本目录仅保留旧 SQL 作为历史参考，不属于 Flyway 管理范围，不作为新部署或升级入口，也不再新增脚本。

新库先执行 `sql/db_init.sql` 的 V1 基线，再启动 Console 自动执行 V2 及之后的迁移。已有库由 Console 的 Flyway 按版本升级，不要重复导入基线或选择性执行本目录脚本。

新增数据库变更统一放入 [正式迁移目录](../../GMD-FAQ-RUNNER-CONSOLE/src/main/resources/db/migration/README.md)，遵守不可修改已应用迁移、幂等和临时库验证约定。

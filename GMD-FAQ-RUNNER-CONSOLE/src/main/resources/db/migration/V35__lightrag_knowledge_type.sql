-- 扩展知识库来源；保留已有 ENUM 值及约束，缺表、缺列或已经支持时跳过。
SET NAMES utf8mb4;

SET @lightrag_kb_type = (
  SELECT column_type FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'gmd_faq_knowledge_base_config'
    AND column_name = 'kb_type' AND data_type = 'enum'
  LIMIT 1
);
SET @lightrag_kb_sql = IF(
  @lightrag_kb_type IS NOT NULL AND LOCATE('''LIGHTRAG''', @lightrag_kb_type) = 0,
  CONCAT('ALTER TABLE `gmd_faq_knowledge_base_config` MODIFY COLUMN `kb_type` ',
    INSERT(@lightrag_kb_type, CHAR_LENGTH(@lightrag_kb_type), 0, ',''LIGHTRAG'''),
    ' NOT NULL COMMENT ''知识库类型'''),
  'SELECT 1'
);
PREPARE lightrag_kb_stmt FROM @lightrag_kb_sql;
EXECUTE lightrag_kb_stmt;
DEALLOCATE PREPARE lightrag_kb_stmt;

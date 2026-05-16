CREATE TABLE IF NOT EXISTS `t_users` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `username`        VARCHAR(50)  NOT NULL,
  `password`        VARCHAR(200) NOT NULL,
  `role`            VARCHAR(20)  NOT NULL DEFAULT 'user',
  `status`          TINYINT      NOT NULL DEFAULT 1,
  `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP,
  `last_login_time` DATETIME,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

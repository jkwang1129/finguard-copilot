#!/bin/sh
(
    set -eu
    case "$FG_COPILOT_DB_PASSWORD" in
        ''|*[!a-f0-9]*) echo 'Expected a generated hexadecimal Copilot database password' >&2; exit 1 ;;
    esac
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
CREATE DATABASE copilot;
CREATE USER 'copilot'@'%' IDENTIFIED BY '${FG_COPILOT_DB_PASSWORD}';
GRANT ALL ON copilot.* TO 'copilot'@'%';
SQL
)

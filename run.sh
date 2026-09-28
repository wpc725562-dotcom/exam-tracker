#!/usr/bin/env bash
# =============================================================================
#  exam-tracker —— 一键启动（Linux / macOS / Git Bash）
#  ---------------------------------------------------------------------------
#  用法：
#    ./run.sh          启动应用（库不存在时会询问是否初始化）
#    ./run.sh init     只建库 + 灌演示数据，然后退出
#    ./run.sh skip     跳过所有数据库检查，直接启动
#
#  和 run.cmd 是同一套逻辑，只是换了 shell。两个都留在仓库里是因为
#  「clone 下来一条命令就能跑」这句话，在 Windows 和 Unix 上不该有两种说法。
# =============================================================================

set -u

cd "$(dirname "$0")" || exit 1

APP_PORT="${SERVER_PORT:-8090}"
DB_PORT="${DB_PORT:-3308}"
DB_NAME="${DB_NAME:-exam_tracker}"
DB_USERNAME="${DB_USERNAME:-dev}"
DB_PASSWORD="${DB_PASSWORD:-dev123456}"

MODE="${1:-start}"

echo
echo "  exam-tracker  launcher"
echo "  ============================================================"
echo

# ---------------------------------------------------------------- 1. Java ---
if ! command -v java >/dev/null 2>&1; then
    echo "  [FAIL] java 不在 PATH 上。请安装 JDK 17 或更高版本后重试。"
    echo
    exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -n 1)"
echo "  [ OK ] $JAVA_VER"

# ------------------------------------------------------- 2. 找到（或构建）jar ---
JAR="$(ls -1 target/exam-tracker-*.jar 2>/dev/null | head -n 1)"
if [ -z "$JAR" ]; then
    echo "  [INFO] target/ 下没有 jar，用 Maven Wrapper 构建..."
    echo
    if [ ! -x "./mvnw" ] && [ ! -f "./mvnw" ]; then
        echo "  [FAIL] 既没有 mvnw，也没有可运行的 jar。"
        echo "         自行构建：mvn -DskipTests package"
        echo "         或从 GitHub Releases 下载 jar。"
        echo
        exit 1
    fi
    if ! ./mvnw -q -DskipTests package; then
        echo
        echo "  [FAIL] 构建失败，请看上面的输出。"
        echo
        exit 1
    fi
    JAR="$(ls -1 target/exam-tracker-*.jar 2>/dev/null | head -n 1)"
    if [ -z "$JAR" ]; then
        echo "  [FAIL] 构建报成功但没有产出 jar。"
        echo
        exit 1
    fi
fi
echo "  [ OK ] jar: $JAR"

# ------------------------------------------------------------ 3. MySQL 可达 ---
DB_UP=0
if [ "$MODE" != "skip" ]; then
    echo "  [INFO] 检查 127.0.0.1:${DB_PORT} 上的 MySQL ..."
    if command -v nc >/dev/null 2>&1 && nc -z 127.0.0.1 "$DB_PORT" 2>/dev/null; then
        DB_UP=1
    elif (echo > "/dev/tcp/127.0.0.1/${DB_PORT}") >/dev/null 2>&1; then
        DB_UP=1
    fi

    if [ "$DB_UP" = "0" ]; then
        echo "  [WARN] 端口 ${DB_PORT} 上没有服务在监听，应用会因为连不上库而启动失败。"
        echo
        echo "         可以："
        echo "           1. 先启动你的 MySQL，再跑一次本脚本"
        echo "           2. 换端口：DB_PORT=3306 ./run.sh"
        echo "           3. 用 Docker：见 README 第 4.5 节"
        echo
        if [ "$MODE" != "init" ]; then
            printf "  仍然继续启动？[y/N] "
            read -r ans
            case "$ans" in
                [yY]*) ;;
                *) echo "  已取消。"; exit 1 ;;
            esac
        fi
    else
        echo "  [ OK ] MySQL 在监听。"
    fi
fi

# --------------------------------------------------- 4. 建表 + 演示数据 ---
if [ "$DB_UP" = "1" ]; then
    if ! command -v mysql >/dev/null 2>&1; then
        echo "  [INFO] mysql 客户端不在 PATH 上，跳过数据库检查。"
        echo "         全新环境请手动执行 sql/schema.sql 与 sql/seed.sql。"
    elif mysql -h 127.0.0.1 -P "$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" \
             -e "USE $DB_NAME" >/dev/null 2>&1; then
        echo "  [ OK ] 数据库 \"$DB_NAME\" 已存在。"
    else
        echo "  [WARN] 用账号 \"$DB_USERNAME\" 连不上数据库 \"$DB_NAME\"。"
        DO_INIT="N"
        if [ "$MODE" = "init" ]; then
            DO_INIT="Y"
        else
            printf "  现在创建它并灌入演示数据？[y/N] "
            read -r ans
            case "$ans" in
                [yY]*) DO_INIT="Y" ;;
            esac
        fi

        if [ "$DO_INIT" = "Y" ]; then
            echo "  [INFO] 执行 sql/schema.sql ..."
            if ! mysql -h 127.0.0.1 -P "$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" < sql/schema.sql; then
                echo "  [FAIL] schema.sql 执行失败，请检查账号密码。"
                echo
                exit 1
            fi
            echo "  [INFO] 执行 sql/seed.sql ..."
            if ! mysql -h 127.0.0.1 -P "$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" < sql/seed.sql; then
                echo "  [FAIL] seed.sql 执行失败。"
                echo
                exit 1
            fi
            echo "  [ OK ] 数据库就绪。"
        fi
    fi
fi

if [ "$MODE" = "init" ]; then
    echo
    echo "  完成。启动应用：./run.sh"
    echo
    exit 0
fi

# --------------------------------------------------------------- 5. 启动 ---
echo
echo "  启动 exam-tracker ..."
echo
echo "     网页界面 : http://127.0.0.1:${APP_PORT}/api/"
echo "     接口文档 : http://127.0.0.1:${APP_PORT}/api/doc.html"
echo "     演示账号 : demo / demo123456"
echo
echo "  按 Ctrl+C 停止。"
echo

exec java -jar "$JAR"

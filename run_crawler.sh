#!/bin/bash
# ============================================================
# run_crawler.sh
# Chạy file movie-crawler-jar-with-dependencies.jar mỗi 5 giây
# ============================================================

JAR_PATH="/home/crawler/movie-crawler-jar-with-dependencies.jar"
LOG_DIR="/home/crawler/logs"
RUN_COUNT=0

mkdir -p "$LOG_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Shell script started. Running JAR every 5 seconds."
echo "[$(date '+%Y-%m-%d %H:%M:%S')] JAR: $JAR_PATH"

while true; do
    RUN_COUNT=$((RUN_COUNT + 1))
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    echo ""
    echo "=========================================="
    echo "[$TIMESTAMP] Run #$RUN_COUNT"
    echo "=========================================="

    # Chạy JAR và ghi log
    java -jar "$JAR_PATH" 2>&1 | tee -a "$LOG_DIR/crawler_$(date '+%Y%m%d').log"

    EXIT_CODE=$?
    if [ $EXIT_CODE -ne 0 ]; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] WARNING: JAR exited with code $EXIT_CODE"
    fi

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Sleeping 5 seconds before next run..."
    sleep 5
done

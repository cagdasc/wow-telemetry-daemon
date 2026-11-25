#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DAEMON_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ANSI color codes
BOLD='\033[1m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Default settings
WS_ADDR="${WOW_WS_ADDR:-0.0.0.0:8765}"
STREAM_FORMAT="${WOW_FORMAT:-protobuf}"

# Candidate log directories (Wine / Lutris / Steam / Bottles / macOS / sample_log)
find_default_log_dir() {
    if [[ -n "${WOW_COMBAT_LOG_DIR:-}" && -d "${WOW_COMBAT_LOG_DIR}" ]]; then
        echo "${WOW_COMBAT_LOG_DIR}"
        return
    fi

    local candidates=(
        "$HOME/Games/world-of-warcraft/drive_c/Program Files (x86)/World of Warcraft/_retail_/Logs"
        "$HOME/.wine/drive_c/Program Files (x86)/World of Warcraft/_retail_/Logs"
        "$HOME/.var/app/com.usebottles.bottles/data/bottles/bottles/*/drive_c/Program Files (x86)/World of Warcraft/_retail_/Logs"
        "/Applications/World of Warcraft/_retail_/Logs"
        "${DAEMON_DIR}/sample_log"
    )

    for path in "${candidates[@]}"; do
        if [[ -d "$path" ]]; then
            echo "$path"
            return
        fi
    done

    echo "${DAEMON_DIR}/sample_log"
}

LOG_DIR="$(find_default_log_dir)"

# Locate daemon executable strictly in the same directory as this script
find_daemon_cmd() {
    local daemon_path="${SCRIPT_DIR}/wow-telemetry-daemon"
    if [[ ! -f "$daemon_path" ]]; then
        echo -e "${RED}Error: Daemon executable not found in script directory: ${daemon_path}${NC}" >&2
        echo -e "${YELLOW}Please build the daemon and place 'wow-telemetry-daemon' beside this script.${NC}" >&2
        exit 1
    elif [[ ! -x "$daemon_path" ]]; then
        chmod +x "$daemon_path" 2>/dev/null || true
    fi
    echo "$daemon_path"
}

human_size() {
    local bytes=$1
    if [[ $bytes -ge 1048576 ]]; then
        echo "$((bytes / 1048576)) MB"
    elif [[ $bytes -ge 1024 ]]; then
        echo "$((bytes / 1024)) KB"
    else
        echo "${bytes} B"
    fi
}

get_combat_logs() {
    local dir="$1"
    if [[ -d "$dir" ]]; then
        find "$dir" -maxdepth 1 -type f \( -name "WoWCombatLog*.txt" -o -name "*.txt" \) 2>/dev/null | sort -r
    fi
}

run_new_log() {
    local daemon_cmd
    daemon_cmd="$(find_daemon_cmd)"
    if [[ -z "$daemon_cmd" ]]; then
        echo -e "${RED}Error: Daemon executable not found and cargo is not installed.${NC}"
        exit 1
    fi

    if [[ ! -d "$LOG_DIR" ]]; then
        echo -e "${YELLOW}Combat log directory not found: ${LOG_DIR}${NC}"
        read -r -p "Enter valid combat log directory: " custom_dir
        if [[ -d "$custom_dir" ]]; then
            LOG_DIR="$custom_dir"
        else
            echo -e "${RED}Invalid directory. Aborting.${NC}"
            return 1
        fi
    fi

    local timestamp
    timestamp="$(date +%Y%m%d-%H%M%S)-$$"
    local backup_dir="${LOG_DIR}/Backups/${timestamp}"

    local existing_logs=()
    while IFS= read -r file; do
        [[ -n "$file" ]] && existing_logs+=("$file")
    done < <(get_combat_logs "$LOG_DIR")

    if [[ ${#existing_logs[@]} -gt 0 ]]; then
        mkdir -p "$backup_dir"
        echo -e "${CYAN}Backing up ${#existing_logs[@]} log file(s) to ${backup_dir}...${NC}"
        for f in "${existing_logs[@]}"; do
            cp "$f" "$backup_dir/"
            rm "$f"
        done
        echo -e "${GREEN}Old logs moved to backup directory.${NC}"
    fi

    echo -e "${CYAN}Waiting for WoW to create a new combat log in ${LOG_DIR}...${NC}"
    local new_log=""
    while [[ -z "$new_log" ]]; do
        sleep 0.2
        new_log="$(find "$LOG_DIR" -maxdepth 1 -type f -name "WoWCombatLog*.txt" 2>/dev/null | head -n 1)"
    done

    echo -e "${GREEN}Found new combat log:${NC} ${new_log}"
    echo -e "${CYAN}Starting WebSocket stream on ws://${WS_ADDR} (${STREAM_FORMAT})...${NC}"
    exec $daemon_cmd "$new_log" --from-start --quiet --format "$STREAM_FORMAT" --ws-addr "$WS_ADDR"
}

run_latest_log() {
    local daemon_cmd
    daemon_cmd="$(find_daemon_cmd)"
    if [[ -z "$daemon_cmd" ]]; then
        echo -e "${RED}Error: Daemon executable not found and cargo is not installed.${NC}"
        exit 1
    fi

    local latest_log
    latest_log="$(get_combat_logs "$LOG_DIR" | head -n 1)"

    if [[ -z "$latest_log" ]]; then
        echo -e "${YELLOW}No combat logs found in ${LOG_DIR}.${NC}"
        return 1
    fi

    echo -e "${GREEN}Using latest combat log:${NC} ${latest_log}"
    echo -e "${CYAN}Starting WebSocket stream on ws://${WS_ADDR} (${STREAM_FORMAT})...${NC}"
    exec $daemon_cmd "$latest_log" --from-start --quiet --format "$STREAM_FORMAT" --ws-addr "$WS_ADDR"
}

run_pick_log() {
    local daemon_cmd
    daemon_cmd="$(find_daemon_cmd)"
    if [[ -z "$daemon_cmd" ]]; then
        echo -e "${RED}Error: Daemon executable not found and cargo is not installed.${NC}"
        exit 1
    fi

    echo -e "\n${BOLD}Available Combat Logs:${NC}"
    local log_files=()

    # Search in configured LOG_DIR and sample_log
    while IFS= read -r f; do
        [[ -n "$f" ]] && log_files+=("$f")
    done < <(
        {
            get_combat_logs "$LOG_DIR"
            if [[ "$LOG_DIR" != "${DAEMON_DIR}/sample_log" ]]; then
                get_combat_logs "${DAEMON_DIR}/sample_log"
            fi
        } | sort -u
    )

    if [[ ${#log_files[@]} -eq 0 ]]; then
        echo -e "${YELLOW}No log files found in ${LOG_DIR} or ${DAEMON_DIR}/sample_log.${NC}"
        read -r -p "Enter full path to a combat log file: " manual_file
        if [[ -f "$manual_file" ]]; then
            run_custom_file "$manual_file"
        else
            echo -e "${RED}File does not exist.${NC}"
        fi
        return
    fi

    local i=1
    for f in "${log_files[@]}"; do
        local sz
        sz="$(wc -c < "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null || echo 0)"
        local h_sz
        h_sz="$(human_size "$sz")"
        local fname
        fname="$(basename "$f")"
        local parent
        parent="$(basename "$(dirname "$f")")"
        printf "  ${CYAN}[%2d]${NC} %-36s ${YELLOW}(%s)${NC} [%s]\n" "$i" "$fname" "$h_sz" "$parent"
        ((i++))
    done

    echo ""
    read -r -p "Select a log file [1-${#log_files[@]}] (or 'q' to cancel): " choice
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#log_files[@]} )); then
        local selected="${log_files[$((choice - 1))]}"
        run_custom_file "$selected"
    else
        echo "Cancelled."
    fi
}

run_custom_file() {
    local target_file="$1"
    local daemon_cmd
    daemon_cmd="$(find_daemon_cmd)"
    if [[ -z "$daemon_cmd" ]]; then
        echo -e "${RED}Error: Daemon executable not found and cargo is not installed.${NC}"
        exit 1
    fi

    if [[ ! -f "$target_file" ]]; then
        echo -e "${RED}File not found: ${target_file}${NC}"
        return 1
    fi

    echo -e "${GREEN}Streaming log file:${NC} ${target_file}"
    echo -e "${CYAN}Waiting for WebSocket client to connect on ws://${WS_ADDR} (${STREAM_FORMAT})...${NC}"
    echo -e "${YELLOW}Once the log is streamed, the daemon will automatically terminate.${NC}"
    exec $daemon_cmd --file "$target_file" --quiet --format "$STREAM_FORMAT" --ws-addr "$WS_ADDR"
}

change_settings() {
    echo -e "\n${BOLD}Current Settings:${NC}"
    echo -e "  Log Directory:     ${CYAN}${LOG_DIR}${NC}"
    echo -e "  WebSocket Address: ${CYAN}${WS_ADDR}${NC}"
    echo -e "  Stream Format:     ${CYAN}${STREAM_FORMAT}${NC}"
    echo ""
    read -r -p "Change log directory? (press Enter to keep current): " new_dir
    if [[ -n "$new_dir" && -d "$new_dir" ]]; then
        LOG_DIR="$new_dir"
    fi

    read -r -p "Change WebSocket address? (press Enter to keep current): " new_addr
    if [[ -n "$new_addr" ]]; then
        WS_ADDR="$new_addr"
    fi

    read -r -p "Change stream format (protobuf/json)? (press Enter to keep current): " new_fmt
    if [[ "$new_fmt" == "protobuf" || "$new_fmt" == "json" ]]; then
        STREAM_FORMAT="$new_fmt"
    fi
    echo -e "${GREEN}Settings updated.${NC}"
}

# Handle CLI arguments directly if provided
while [[ $# -gt 0 ]]; do
    case "$1" in
        --new)
            run_new_log
            exit 0
            ;;
        --latest)
            run_latest_log
            exit 0
            ;;
        --file|-f)
            shift
            run_custom_file "$1"
            exit 0
            ;;
        --log-dir)
            shift
            LOG_DIR="$1"
            ;;
        --ws-addr)
            shift
            WS_ADDR="$1"
            ;;
        --format)
            shift
            STREAM_FORMAT="$1"
            ;;
        --help|-h)
            echo "Usage: ./run.sh [OPTIONS]"
            echo "Options:"
            echo "  --new              Back up logs and wait for a new combat log"
            echo "  --latest           Tail the latest combat log"
            echo "  --file <PATH>      Stream an existing combat log file (--file) and terminate"
            echo "  --log-dir <DIR>    Set combat log directory"
            echo "  --ws-addr <ADDR>   Set WebSocket address (default: 0.0.0.0:8765)"
            echo "  --format <FMT>     Set format: protobuf (default) or json"
            exit 0
            ;;
        *)
            if [[ -f "$1" ]]; then
                run_custom_file "$1"
                exit 0
            fi
            ;;
    esac
    shift
done

# Interactive Menu Loop
while true; do
    echo -e "\n${BOLD}==================================================${NC}"
    echo -e "${CYAN}${BOLD}       WoW Telemetry Launcher (Linux/macOS)       ${NC}"
    echo -e "${BOLD}==================================================${NC}"
    echo -e "  Log Folder:  ${YELLOW}${LOG_DIR}${NC}"
    echo -e "  WebSocket:   ${YELLOW}ws://${WS_ADDR}${NC} (${STREAM_FORMAT})"
    echo -e "--------------------------------------------------"
    echo -e "  ${BOLD}[1]${NC} ${GREEN}New Live Log${NC}     - Back up old logs, wait for WoW to create a new one"
    echo -e "  ${BOLD}[2]${NC} ${GREEN}Latest Live Log${NC}  - Tail the latest combat log from start"
    echo -e "  ${BOLD}[3]${NC} ${CYAN}Pick Existing${NC}    - Choose a completed log file to stream & exit"
    echo -e "  ${BOLD}[4]${NC} ${CYAN}Custom Log File${NC}  - Enter path to an existing log file to stream & exit"
    echo -e "  ${BOLD}[5]${NC} Settings        - Change directory, WebSocket address, or format"
    echo -e "  ${BOLD}[q]${NC} Quit"
    echo -e "--------------------------------------------------"
    read -r -p "Choose an option [1-5, q]: " menu_choice

    case "$menu_choice" in
        1)
            run_new_log
            ;;
        2)
            run_latest_log
            ;;
        3)
            run_pick_log
            ;;
        4)
            read -r -p "Enter path to combat log file: " file_path
            run_custom_file "$file_path"
            ;;
        5)
            change_settings
            ;;
        q|Q)
            echo "Bye!"
            exit 0
            ;;
        *)
            echo -e "${RED}Invalid choice.${NC}"
            ;;
    esac
done

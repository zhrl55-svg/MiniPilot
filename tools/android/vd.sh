#!/system/bin/sh
# vd.sh — 虚拟屏短进程控制（Android 12+, shell uid 2000）
#
# 设计：用的时候起一个 app_process 建屏，任务结束 stop 掉。
# 不常驻、不保活、不留残余进程。
#
# 重要：建屏进程是 shell uid 的 app_process，并持有一个 owner=com.android.shell
# 的 VIRTUAL display。银行/支付类 App（建设银行等）会把它识别成「录屏/屏幕共享
# 软件 Shell」并直接拒绝启动。因此：
#   1) 用完必须 stop；
#   2) 空闲超过 VD_IDLE_SECONDS（默认 900 秒）看门狗会自动回收，避免会话中断
#      时留下残余进程。
#
# 用法：
#   vd.sh start [W H DPI]   建屏，打印 displayId
#   vd.sh stop              销毁（含看门狗）
#   vd.sh status            状态（含空闲回收倒计时）
#   vd.sh shot <out.png>    截屏
#   vd.sh tap <x> <y>
#   vd.sh swipe <x1> <y1> <x2> <y2> [ms]
#   vd.sh key <keycode>
#   vd.sh text <string>
#   vd.sh launch <pkg/act>  在该屏启动
#   vd.sh info              屏的尺寸/状态
#
# 环境变量：
#   VD_IDLE_SECONDS  空闲自动回收秒数，默认 900（0 = 不自动回收）

VD=/data/local/tmp/vd
STATE=$VD/state
DEX=$VD/classes.dex
HB=$VD/hb                 # 心跳文件，每次使用都 touch
WD=$VD/vd_watchdog.sh      # 看门狗脚本（首次 start 时生成）
WD_PID=$VD/watchdog.pid
IDLE_FILE=$VD/idle_seconds

VD_IDLE_SECONDS=${VD_IDLE_SECONDS:-900}
case "$VD_IDLE_SECONDS" in
  ''|*[!0-9]*) VD_IDLE_SECONDS=900 ;;
esac

touch_hb() {
  touch "$HB" 2>/dev/null
}

# 看门狗：空闲超过阈值就 kill 掉建屏进程并清掉 state，
# 这样即使会话意外结束，也不会留下被银行 App 判定为「屏幕共享」的残余进程。
write_watchdog() {
  cat > "$WD" <<'WDEOF'
#!/system/bin/sh
VD=/data/local/tmp/vd
STATE=$VD/state
HB=$VD/hb
IDLE=$(cat "$VD/idle_seconds" 2>/dev/null || echo 900)
case "$IDLE" in ''|*[!0-9]*) IDLE=900 ;; esac
while true; do
  sleep 15
  [ -f "$STATE" ] || continue
  read pid did < "$STATE" 2>/dev/null || continue
  [ -d "/proc/$pid" ] || continue
  [ -f "$HB" ] || continue
  now=$(date +%s 2>/dev/null || echo 0)
  hb=$(stat -c %Y "$HB" 2>/dev/null || echo 0)
  case "$now" in ''|*[!0-9]*) continue ;; esac
  case "$hb" in ''|*[!0-9]*) continue ;; esac
  [ "$now" -gt "$hb" ] || continue
  [ $((now - hb)) -ge "$IDLE" ] || continue
  echo "[vd-watchdog] idle ${IDLE}s -> stopping display ${did} (pid ${pid}) $(date '+%F %T')" >> "$VD/watchdog.log"
  kill "$pid" 2>/dev/null
  sleep 1
  [ -d "/proc/$pid" ] && kill -9 "$pid" 2>/dev/null
  rm -f "$STATE"
  exit 0
done
WDEOF
  chmod 755 "$WD" 2>/dev/null
}

ensure_watchdog() {
  [ "$VD_IDLE_SECONDS" -gt 0 ] 2>/dev/null || return 0
  if [ -f "$WD_PID" ]; then
    read wpid < "$WD_PID" 2>/dev/null
    if [ -n "$wpid" ] && [ -d "/proc/$wpid" ]; then return 0; fi
  fi
  write_watchdog
  nohup /system/bin/sh "$WD" >>"$VD/watchdog.log" 2>&1 &
  echo $! > "$WD_PID"
}

stop_watchdog() {
  [ -f "$WD_PID" ] || return 0
  read wpid < "$WD_PID" 2>/dev/null
  if [ -n "$wpid" ] && [ -d "/proc/$wpid" ]; then
    kill "$wpid" 2>/dev/null
    sleep 0.3
    [ -d "/proc/$wpid" ] && kill -9 "$wpid" 2>/dev/null
  fi
  rm -f "$WD_PID"
}

need_display() {
  [ -f "$STATE" ] || { echo "ERR: no display (run start first)" >&2; return 1; }
  read pid did < "$STATE"
  [ -d "/proc/$pid" ] || { echo "ERR: display process gone" >&2; rm -f "$STATE"; return 1; }
  [ -n "$did" ] || { echo "ERR: bad state" >&2; return 1; }
  touch_hb
  return 0
}

do_start() {
  W=${1:-720}; H=${2:-1600}; DPI=${3:-320}
  if [ -f "$STATE" ]; then
    read pid did < "$STATE"
    if [ -d "/proc/$pid" ]; then
      echo "$VD_IDLE_SECONDS" > "$IDLE_FILE"
      touch_hb
      ensure_watchdog
      echo "$did"
      return 0
    fi
    rm -f "$STATE"
  fi
  [ -f "$DEX" ] || { echo "ERR: dex missing at $DEX" >&2; return 1; }
  rm -f "$VD/out.log"
  CLASSPATH=$DEX nohup app_process /system/bin VdTest "$W" "$H" "$DPI" > "$VD/out.log" 2>&1 &
  vpid=$!
  i=0
  while [ $i -lt 40 ]; do
    sleep 0.3
    did=$(sed -n 's/^DISPLAY_ID=//p' "$VD/out.log" 2>/dev/null | head -1)
    if [ -n "$did" ]; then
      echo "$vpid $did" > "$STATE"
      echo "$VD_IDLE_SECONDS" > "$IDLE_FILE"
      touch_hb
      ensure_watchdog
      echo "$did"
      return 0
    fi
    if [ ! -d "/proc/$vpid" ]; then
      echo "ERR: process died: $(cat "$VD/out.log" 2>/dev/null)" >&2
      return 1
    fi
    i=$((i + 1))
  done
  echo "ERR: timeout waiting for display" >&2
  cat "$VD/out.log" >&2 2>/dev/null
  kill "$vpid" 2>/dev/null
  return 1
}

do_stop() {
  stop_watchdog
  if [ ! -f "$STATE" ]; then echo "not running"; rm -f "$HB" "$IDLE_FILE"; return 0; fi
  read pid did < "$STATE"
  kill "$pid" 2>/dev/null
  i=0
  while [ $i -lt 20 ] && [ -d "/proc/$pid" ]; do sleep 0.2; i=$((i + 1)); done
  [ -d "/proc/$pid" ] && kill -9 "$pid" 2>/dev/null
  rm -f "$STATE" "$HB" "$IDLE_FILE"
  # 屏上启动过的 App 会被系统迁移到主屏后台，交给 Android 自己回收，不强制结束
  echo "stopped display $did"
}

do_status() {
  if need_display 2>/dev/null; then
    echo "running display $did (pid $pid)"
  else
    echo "not running"
    return 0
  fi
  now=$(date +%s 2>/dev/null || echo 0)
  hb=$(stat -c %Y "$HB" 2>/dev/null || echo 0)
  if [ "$now" -gt "$hb" ] 2>/dev/null; then
    echo "idle ${VD_IDLE_SECONDS}s (last use $((now - hb))s ago)"
  fi
  if [ -f "$WD_PID" ]; then
    read wpid < "$WD_PID" 2>/dev/null
    if [ -n "$wpid" ] && [ -d "/proc/$wpid" ]; then
      echo "watchdog running (pid $wpid)"
    else
      echo "watchdog not running"
    fi
  else
    echo "watchdog not running"
  fi
}

case "$1" in
  start)  shift; do_start "$@" ;;
  stop)   do_stop ;;
  status) do_status ;;
  shot)   need_display && screencap -d "$did" -p "$2" && ls -la "$2" ;;
  tap)    need_display && input -d "$did" tap "$2" "$3" && echo "tap $2,$3 @ $did" ;;
  swipe)  need_display && input -d "$did" swipe "$2" "$3" "$4" "$5" "${6:-300}" && echo "swipe @ $did" ;;
  key)    need_display && input -d "$did" keyevent "$2" && echo "key $2 @ $did" ;;
  text)   need_display && input -d "$did" text "$2" && echo "text $2 @ $did" ;;
  paste)  need_display && input -d "$did" keyevent 279 && echo "paste @ $did" ;;
  # 批量执行：一次 shell 调用做完多个动作，省掉每次 ~0.6s 的 IPC 开销
  #   vd.sh do "tap 100 200" "sleep 0.5" "tap 300 400" "key 4"
  do)     need_display || exit 1
          shift
          for c in "$@"; do
            touch_hb
            case "$c" in
              sleep*) $c ;;
              tap*)   input -d "$did" tap ${c#tap } ;;
              swipe*) input -d "$did" swipe ${c#swipe } ;;
              key*)   input -d "$did" keyevent ${c#key } ;;
              text*)  input -d "$did" text "${c#text }" ;;
              paste*) input -d "$did" keyevent 279 ;;
              shot*)  screencap -d "$did" -p "${c#shot }" ;;
              *) echo "skip: $c" >&2 ;;
            esac
          done
          echo "done $# actions @ $did" ;;
  # 等目标 App 成为前台 Activity（快，一次 dumpsys，不用轮询截图）
  wait-app)
          max=${2:-20}; pkg=$3
          [ -n "$pkg" ] || { echo "usage: wait-app <timeout> <pkg>" >&2; exit 2; }
          i=0
          while [ $i -lt $((max * 2)) ]; do
            touch_hb
            if dumpsys activity activities 2>/dev/null | grep -q "mResumedActivity.*$pkg"; then
              echo "resumed: $pkg ($((i / 2))s)"; exit 0
            fi
            sleep 0.5; i=$((i + 1))
          done
          echo "timeout waiting $pkg" >&2; exit 1 ;;
  # 等 App 真正可见：轮询截图体积，超过阈值说明不再是黑屏
  wait-ready)
          need_display || exit 1
          max=${2:-20}; thr=${3:-30000}
          i=0
          while [ $i -lt $((max * 2)) ]; do
            touch_hb
            screencap -d "$did" -p "$VD/ready.png" 2>/dev/null
            sz=$(stat -c%s "$VD/ready.png" 2>/dev/null || echo 0)
            [ "$sz" -gt "$thr" ] && { echo "ready ($sz bytes, $(($i / 2))s)"; exit 0; }
            sleep 0.5; i=$((i + 1))
          done
          echo "not ready after ${max}s" >&2; exit 1 ;;
  # 启动并强制冷启动：已有实例时 am start --display 不会重建窗口（渲染黑屏），
  # 必须先 force-stop。实测冷启动 1.66s，而复用实例可能永久黑屏。
  launch)
          need_display || exit 1
          pkg=${2%%/*}
          if pidof "$pkg" >/dev/null 2>&1; then
            am force-stop "$pkg" >/dev/null 2>&1
            echo "force-stopped $pkg (had running instance)"
            sleep 0.8
          fi
          am start --display "$did" -n "$2" 2>&1 | head -3 ;;
  info)
    if need_display 2>/dev/null; then
      dumpsys display | grep -A1 "mDisplayId=$did" | head -2
    else echo "not running"; fi ;;
  *) echo "usage: vd.sh start|stop|status|shot|tap|swipe|key|text|launch|info" >&2; exit 2 ;;
esac

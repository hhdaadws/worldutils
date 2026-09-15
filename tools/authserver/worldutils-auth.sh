#!/usr/bin/env bash
# WorldUtils 账号授权服务管理脚本（在你自己的电脑上运行，Git Bash 可用）
#
# 服务端只是 nginx 静态文件目录，无任何后端程序：
#   /var/www/worldutils-auth/auth/<SHA256(账号)>.dat  ← mod 拉取的账号文件
#     内容: salt:SHA256(salt:密码):到期毫秒
#   /var/lib/worldutils-auth/index.tsv                ← 账号索引（不对外，供 list/renew）
#     每行: 账号<TAB>哈希<TAB>到期毫秒
#
# 用法:
#   ./worldutils-auth.sh setup                     一键安装并配置 nginx（端口 10000）
#   ./worldutils-auth.sh add <账号> <天数> [密码]   添加账号（不给密码则随机生成并打印）
#   ./worldutils-auth.sh renew <账号> <天数>        续期（从现在起重新计 N 天）
#   ./worldutils-auth.sh passwd <账号> [新密码]     改密码（保持原到期时间）
#   ./worldutils-auth.sh del <账号>                 删除账号
#   ./worldutils-auth.sh list                       列出全部账号及到期时间
#
# 环境变量:
#   SSH_TARGET  服务器 SSH 目标，默认 root@104.62.94.44
#   AUTH_PORT   nginx 端口，默认 10000（改了要同步改 mod 里 AccountAuth.BASE_URL）
set -euo pipefail

SSH_TARGET="${SSH_TARGET:-root@104.62.94.44}"
AUTH_PORT="${AUTH_PORT:-10000}"
WEB_DIR="/var/www/worldutils-auth"
IDX="/var/lib/worldutils-auth/index.tsv"

die() { echo "错误: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "本机缺少命令: $1"; }
need ssh; need sha256sum; need openssl

rssh() { ssh -o ConnectTimeout=10 "$SSH_TARGET" "$@"; }

sha256() { printf '%s' "$1" | sha256sum | awk '{print $1}'; }

now_ms() { echo $(( $(date +%s) * 1000 )); }

expiry_ms() { # $1 = 天数
    echo $(( ( $(date +%s) + $1 * 86400 ) * 1000 ))
}

fmt_expiry() { # $1 = 毫秒时间戳 → 可读日期（本机时区）
    local s=$(( $1 / 1000 ))
    if date -d "@$s" '+%Y-%m-%d %H:%M' 2>/dev/null; then return; fi
    date -r "$s" '+%Y-%m-%d %H:%M' 2>/dev/null || echo "$1"
}

gen_password() { openssl rand -base64 12 | tr -d '/+=' | cut -c1-12; }

cmd_setup() {
    echo "== 在 $SSH_TARGET 上安装并配置 nginx（端口 $AUTH_PORT）=="
    rssh AUTH_PORT="$AUTH_PORT" 'bash -s' <<'EOF'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq nginx
fi
mkdir -p /var/www/worldutils-auth/auth /var/lib/worldutils-auth
touch /var/lib/worldutils-auth/index.tsv
chmod 700 /var/lib/worldutils-auth
cat > /etc/nginx/conf.d/worldutils-auth.conf <<CONF
server {
    listen ${AUTH_PORT};
    server_name _;
    root /var/www/worldutils-auth;
    # 只允许按精确文件名取 .dat，禁止目录列表，防止扫库
    autoindex off;
    location /auth/ {
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 404; }
    access_log /var/log/nginx/worldutils-auth.access.log;
}
CONF
nginx -t
systemctl enable --now nginx
systemctl reload nginx
# 防火墙（有 ufw 且启用时放行）
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    ufw allow ${AUTH_PORT}/tcp >/dev/null
fi
echo "OK: nginx 已就绪，监听 ${AUTH_PORT}，账号目录 /var/www/worldutils-auth/auth/"
EOF
    echo "== 完成。测试: curl http://${SSH_TARGET#*@}:$AUTH_PORT/auth/_.dat 应返回 404 =="
}

# 上传单个账号文件 + 更新索引。参数: 账号 哈希 salt passhash 到期毫秒
push_account() {
    local user="$1" hash="$2" salt="$3" passhash="$4" expiry="$5"
    printf '%s:%s:%s' "$salt" "$passhash" "$expiry" \
        | rssh "cat > '$WEB_DIR/auth/$hash.dat' && chmod 644 '$WEB_DIR/auth/$hash.dat' \
            && grep -v \"	$hash	\" '$IDX' > '$IDX.tmp' 2>/dev/null || true; \
            printf '%s\t%s\t%s\n' '$user' '$hash' '$expiry' >> '$IDX.tmp' && mv '$IDX.tmp' '$IDX'"
}

cmd_add() {
    local user="${1:?用法: add <账号> <天数> [密码]}" days="${2:?缺少天数}" pass="${3:-}"
    [[ "$days" =~ ^[0-9]+$ ]] || die "天数必须是整数"
    local generated=""
    if [[ -z "$pass" ]]; then pass="$(gen_password)"; generated=1; fi
    local hash salt passhash expiry
    hash="$(sha256 "$user")"
    if rssh "test -f '$WEB_DIR/auth/$hash.dat'"; then
        die "账号 $user 已存在（用 renew 续期或 passwd 改密码）"
    fi
    salt="$(openssl rand -hex 8)"
    passhash="$(sha256 "$salt:$pass")"
    expiry="$(expiry_ms "$days")"
    push_account "$user" "$hash" "$salt" "$passhash" "$expiry"
    echo "已添加账号:"
    echo "  账号: $user"
    [[ -n "$generated" ]] && echo "  密码: $pass  （随机生成，务必现在记下发给用户）" \
                          || echo "  密码: (你指定的)"
    echo "  到期: $(fmt_expiry "$expiry")（$days 天）"
    echo "  游戏内登录: /miner login $user <密码>  或  /farm login $user <密码>"
}

cmd_renew() {
    local user="${1:?用法: renew <账号> <天数>}" days="${2:?缺少天数}"
    [[ "$days" =~ ^[0-9]+$ ]] || die "天数必须是整数"
    local hash expiry
    hash="$(sha256 "$user")"
    rssh "test -f '$WEB_DIR/auth/$hash.dat'" || die "账号 $user 不存在"
    expiry="$(expiry_ms "$days")"
    # 只替换第三段到期时间，salt 和密码哈希原样保留
    rssh "awk -F: -v e='$expiry' '{print \$1\":\"\$2\":\"e}' '$WEB_DIR/auth/$hash.dat' > '$WEB_DIR/auth/$hash.dat.tmp' \
        && mv '$WEB_DIR/auth/$hash.dat.tmp' '$WEB_DIR/auth/$hash.dat' && chmod 644 '$WEB_DIR/auth/$hash.dat' \
        && grep -v \"	$hash	\" '$IDX' > '$IDX.tmp' 2>/dev/null || true; \
        printf '%s\t%s\t%s\n' '$user' '$hash' '$expiry' >> '$IDX.tmp' && mv '$IDX.tmp' '$IDX'"
    echo "已续期 $user 至 $(fmt_expiry "$expiry")（从现在起 $days 天）"
    echo "提示: 用户需重新执行一次 /miner login 才会取到新的到期时间"
}

cmd_passwd() {
    local user="${1:?用法: passwd <账号> [新密码]}" pass="${2:-}"
    local hash old expiry salt passhash generated=""
    hash="$(sha256 "$user")"
    old="$(rssh "cat '$WEB_DIR/auth/$hash.dat'" 2>/dev/null)" || die "账号 $user 不存在"
    expiry="${old##*:}"
    if [[ -z "$pass" ]]; then pass="$(gen_password)"; generated=1; fi
    salt="$(openssl rand -hex 8)"
    passhash="$(sha256 "$salt:$pass")"
    push_account "$user" "$hash" "$salt" "$passhash" "$expiry"
    echo "已重置 $user 的密码，到期时间不变（$(fmt_expiry "$expiry")）"
    [[ -n "$generated" ]] && echo "  新密码: $pass  （随机生成，务必现在记下发给用户）"
}

cmd_del() {
    local user="${1:?用法: del <账号>}"
    local hash
    hash="$(sha256 "$user")"
    rssh "rm -f '$WEB_DIR/auth/$hash.dat'; \
        grep -v \"	$hash	\" '$IDX' > '$IDX.tmp' 2>/dev/null || true; mv '$IDX.tmp' '$IDX'"
    echo "已删除账号 $user（已登录用户在本地授权到期前仍可用，之后无法再登录）"
}

cmd_list() {
    local now rows
    now="$(now_ms)"
    rows="$(rssh "cat '$IDX'" 2>/dev/null)" || { echo "(没有账号)"; return; }
    [[ -z "$rows" ]] && { echo "(没有账号)"; return; }
    printf '%-20s %-18s %s\n' "账号" "到期时间" "状态"
    while IFS=$'\t' read -r user hash expiry; do
        [[ -z "$user" ]] && continue
        local state="有效"
        (( expiry <= now )) && state="已过期"
        printf '%-20s %-18s %s\n' "$user" "$(fmt_expiry "$expiry")" "$state"
    done <<< "$rows"
}

case "${1:-}" in
    setup)  cmd_setup ;;
    add)    shift; cmd_add "$@" ;;
    renew)  shift; cmd_renew "$@" ;;
    passwd) shift; cmd_passwd "$@" ;;
    del)    shift; cmd_del "$@" ;;
    list)   cmd_list ;;
    *)      grep '^#' "$0" | sed -n '2,20p' | sed 's/^# \{0,1\}//'; exit 1 ;;
esac

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
WorldUtils 账号授权管理 GUI（PyQt5）

在你自己的电脑上运行（需已配置好到服务器的 SSH 公钥）：
    conda install pyqt          # Anaconda 通常自带，缺了再装
    python worldutils_auth_gui.py

服务端只是 nginx 静态文件目录，无任何后端程序：
    /var/www/worldutils-auth/auth/<SHA256(账号)>.dat   ← mod 拉取的账号文件
        内容: salt:SHA256(salt:密码):到期毫秒
    /var/lib/worldutils-auth/index.tsv                 ← 账号索引（不对外）
        每行: 账号<TAB>哈希<TAB>到期毫秒

功能与 worldutils-auth.sh 一致：一键配置 nginx / 添加 / 续期 / 改密 / 删除 / 列表。
改端口或 IP 时要同步修改 mod 里 AccountAuth.BASE_URL。
"""

import hashlib
import secrets
import string
import subprocess
import sys
import time
from datetime import datetime

from PyQt5.QtCore import Qt, QThread, pyqtSignal
from PyQt5.QtWidgets import (
    QApplication, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView, QLabel,
    QLineEdit, QMainWindow, QMessageBox, QPlainTextEdit, QPushButton,
    QSpinBox, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget,
)

DEFAULT_SSH_TARGET = "root@104.62.94.44"
DEFAULT_AUTH_PORT = 10000
WEB_DIR = "/var/www/worldutils-auth"
IDX = "/var/lib/worldutils-auth/index.tsv"

# ---------------------------------------------------------------- 工具函数

def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def gen_password(length: int = 12) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def expiry_ms(days: int) -> int:
    return (int(time.time()) + days * 86400) * 1000


def fmt_expiry(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def run_ssh(target: str, script: str, timeout: int = 60) -> str:
    """把 bash 脚本通过 stdin 发给远端执行（UTF-8，避开 Windows 命令行参数编码问题）。"""
    proc = subprocess.run(
        ["ssh", "-o", "ConnectTimeout=10", "-o", "BatchMode=yes", target, "bash -s"],
        input=script.encode("utf-8"),
        capture_output=True,
        timeout=timeout,
    )
    out = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    if proc.returncode != 0:
        raise RuntimeError(err.strip() or out.strip() or f"ssh 退出码 {proc.returncode}")
    return out


# 远端索引更新片段：删掉旧行再追加新行（哈希做键）
def _index_update(user: str, hash_: str, expiry: int) -> str:
    return (
        f"grep -v \"\t{hash_}\t\" '{IDX}' > '{IDX}.tmp' 2>/dev/null || true\n"
        f"printf '%s\\t%s\\t%s\\n' '{user}' '{hash_}' '{expiry}' >> '{IDX}.tmp'\n"
        f"mv '{IDX}.tmp' '{IDX}'\n"
    )


NGINX_SETUP = r"""
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq nginx
fi
mkdir -p {web}/auth /var/lib/worldutils-auth
touch {idx}
chmod 700 /var/lib/worldutils-auth
cat > /etc/nginx/conf.d/worldutils-auth.conf <<'CONF'
server {{
    listen {port};
    server_name _;
    root {web};
    autoindex off;
    location /auth/ {{
        default_type text/plain;
        try_files $uri =404;
    }}
    location / {{ return 404; }}
    access_log /var/log/nginx/worldutils-auth.access.log;
}}
CONF
nginx -t
systemctl enable --now nginx
systemctl reload nginx
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    ufw allow {port}/tcp >/dev/null
fi
echo "OK: nginx 已就绪，监听 {port}，账号目录 {web}/auth/"
"""


# ---------------------------------------------------------------- 后台线程

class SshWorker(QThread):
    """所有 SSH 操作都丢到后台线程，避免卡死界面。"""
    done = pyqtSignal(bool, str)   # (成功?, 输出或错误信息)

    def __init__(self, target: str, script: str, timeout: int = 60, parent=None):
        super().__init__(parent)
        self.target = target
        self.script = script
        self.timeout = timeout

    def run(self):
        try:
            out = run_ssh(self.target, self.script, self.timeout)
            self.done.emit(True, out)
        except subprocess.TimeoutExpired:
            self.done.emit(False, "SSH 执行超时")
        except FileNotFoundError:
            self.done.emit(False, "找不到 ssh 命令（Windows 需安装 OpenSSH 客户端）")
        except Exception as e:  # noqa: BLE001
            self.done.emit(False, str(e))


# ---------------------------------------------------------------- 主窗口

class AuthManager(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("WorldUtils 账号授权管理")
        self.resize(760, 620)
        self.worker = None
        self._build_ui()

    # ---------- UI ----------

    def _build_ui(self):
        root = QWidget()
        layout = QVBoxLayout(root)

        # 服务器设置
        srv_box = QGroupBox("服务器")
        srv = QHBoxLayout(srv_box)
        srv.addWidget(QLabel("SSH 目标:"))
        self.ed_target = QLineEdit(DEFAULT_SSH_TARGET)
        srv.addWidget(self.ed_target, 2)
        srv.addWidget(QLabel("端口:"))
        self.sp_port = QSpinBox()
        self.sp_port.setRange(1, 65535)
        self.sp_port.setValue(DEFAULT_AUTH_PORT)
        srv.addWidget(self.sp_port)
        self.btn_setup = QPushButton("一键配置 nginx")
        self.btn_setup.clicked.connect(self.on_setup)
        srv.addWidget(self.btn_setup)
        layout.addWidget(srv_box)

        # 账号表
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["账号", "到期时间", "状态"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setSelectionMode(QTableWidget.SingleSelection)
        self.table.itemSelectionChanged.connect(self.on_row_selected)
        layout.addWidget(self.table, 2)

        # 操作区
        op_box = QGroupBox("账号操作")
        grid = QGridLayout(op_box)
        grid.addWidget(QLabel("账号:"), 0, 0)
        self.ed_user = QLineEdit()
        grid.addWidget(self.ed_user, 0, 1)
        grid.addWidget(QLabel("密码(留空=随机):"), 0, 2)
        self.ed_pass = QLineEdit()
        grid.addWidget(self.ed_pass, 0, 3)
        grid.addWidget(QLabel("天数:"), 0, 4)
        self.sp_days = QSpinBox()
        self.sp_days.setRange(1, 3650)
        self.sp_days.setValue(30)
        grid.addWidget(self.sp_days, 0, 5)

        self.btn_refresh = QPushButton("刷新列表")
        self.btn_add = QPushButton("添加")
        self.btn_renew = QPushButton("续期")
        self.btn_passwd = QPushButton("改密码")
        self.btn_del = QPushButton("删除")
        self.btn_refresh.clicked.connect(self.on_refresh)
        self.btn_add.clicked.connect(self.on_add)
        self.btn_renew.clicked.connect(self.on_renew)
        self.btn_passwd.clicked.connect(self.on_passwd)
        self.btn_del.clicked.connect(self.on_del)
        btns = QHBoxLayout()
        for b in (self.btn_refresh, self.btn_add, self.btn_renew,
                  self.btn_passwd, self.btn_del):
            btns.addWidget(b)
        grid.addLayout(btns, 1, 0, 1, 6)
        layout.addWidget(op_box)

        # 日志
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        layout.addWidget(self.log_view, 1)

        self.setCentralWidget(root)

    # ---------- 通用 ----------

    def log(self, text: str):
        self.log_view.appendPlainText(text.rstrip())

    def _busy(self, busy: bool):
        for b in (self.btn_setup, self.btn_refresh, self.btn_add,
                  self.btn_renew, self.btn_passwd, self.btn_del):
            b.setEnabled(not busy)

    def _run(self, script: str, on_ok, timeout: int = 60):
        """启动后台 SSH；on_ok(output) 只在成功时回调。"""
        if self.worker is not None and self.worker.isRunning():
            self.log("上一个操作还没结束，请稍候")
            return
        self._busy(True)

        def finished(ok: bool, out: str):
            self._busy(False)
            if ok:
                on_ok(out)
            else:
                self.log(f"失败: {out}")
                QMessageBox.warning(self, "操作失败", out)

        self.worker = SshWorker(self.ed_target.text().strip(), script, timeout, self)
        self.worker.done.connect(finished)
        self.worker.start()

    def _current_user(self) -> str:
        user = self.ed_user.text().strip()
        if not user:
            QMessageBox.warning(self, "缺少账号", "请先填写账号（或在列表里点选一行）")
        return user

    def on_row_selected(self):
        rows = self.table.selectionModel().selectedRows()
        if rows:
            self.ed_user.setText(self.table.item(rows[0].row(), 0).text())

    # ---------- 操作 ----------

    def on_setup(self):
        port = self.sp_port.value()
        if QMessageBox.question(
                self, "确认", f"将在 {self.ed_target.text().strip()} 上安装/配置 nginx（端口 {port}）。\n"
                              f"改了端口要同步改 mod 里 AccountAuth.BASE_URL。继续？"
        ) != QMessageBox.Yes:
            return
        self.log(f"== 配置 nginx（端口 {port}）==")
        script = NGINX_SETUP.format(web=WEB_DIR, idx=IDX, port=port)
        self._run(script, lambda out: (self.log(out), self.on_refresh()), timeout=300)

    def on_refresh(self):
        self.log("刷新账号列表 ...")
        self._run(f"cat '{IDX}' 2>/dev/null || true", self._fill_table)

    def _fill_table(self, out: str):
        now = int(time.time() * 1000)
        rows = [ln.split("\t") for ln in out.splitlines() if ln.count("\t") == 2]
        self.table.setRowCount(len(rows))
        for i, (user, _hash, expiry_str) in enumerate(rows):
            try:
                expiry = int(expiry_str)
            except ValueError:
                expiry = 0
            state = "有效" if expiry > now else "已过期"
            for col, text in enumerate((user, fmt_expiry(expiry), state)):
                item = QTableWidgetItem(text)
                if state == "已过期":
                    item.setForeground(Qt.red)
                self.table.setItem(i, col, item)
        self.log(f"共 {len(rows)} 个账号")

    def on_add(self):
        user = self._current_user()
        if not user:
            return
        password = self.ed_pass.text() or gen_password()
        generated = not self.ed_pass.text()
        days = self.sp_days.value()
        hash_ = sha256_hex(user)
        salt = secrets.token_hex(8)
        passhash = sha256_hex(f"{salt}:{password}")
        expiry = expiry_ms(days)
        script = (
            f"set -euo pipefail\n"
            f"test -f '{WEB_DIR}/auth/{hash_}.dat' && {{ echo '账号已存在（用续期或改密码）' >&2; exit 1; }}\n"
            f"printf '%s' '{salt}:{passhash}:{expiry}' > '{WEB_DIR}/auth/{hash_}.dat'\n"
            f"chmod 644 '{WEB_DIR}/auth/{hash_}.dat'\n"
            + _index_update(user, hash_, expiry)
        )

        def ok(_out):
            self.log(f"已添加账号: {user}")
            if generated:
                self.log(f"  密码: {password}  （随机生成，务必现在记下发给用户）")
            self.log(f"  到期: {fmt_expiry(expiry)}（{days} 天）")
            self.log(f"  游戏内登录: /miner login {user} <密码>  或  /farm login {user} <密码>")
            if generated:
                QMessageBox.information(
                    self, "账号已添加",
                    f"账号: {user}\n密码: {password}\n到期: {fmt_expiry(expiry)}\n\n"
                    f"密码只显示这一次，请现在复制发给用户。")
            self.ed_pass.clear()
            self.on_refresh()

        self._run(script, ok)

    def on_renew(self):
        user = self._current_user()
        if not user:
            return
        days = self.sp_days.value()
        hash_ = sha256_hex(user)
        expiry = expiry_ms(days)
        # 只替换第三段到期时间，salt 和密码哈希原样保留
        script = (
            f"set -euo pipefail\n"
            f"test -f '{WEB_DIR}/auth/{hash_}.dat' || {{ echo '账号不存在' >&2; exit 1; }}\n"
            f"awk -F: -v e='{expiry}' '{{print $1\":\"$2\":\"e}}' '{WEB_DIR}/auth/{hash_}.dat' "
            f"> '{WEB_DIR}/auth/{hash_}.dat.tmp'\n"
            f"mv '{WEB_DIR}/auth/{hash_}.dat.tmp' '{WEB_DIR}/auth/{hash_}.dat'\n"
            f"chmod 644 '{WEB_DIR}/auth/{hash_}.dat'\n"
            + _index_update(user, hash_, expiry)
        )

        def ok(_out):
            self.log(f"已续期 {user} 至 {fmt_expiry(expiry)}（从现在起 {days} 天）")
            self.log("提示: 用户需重新执行一次 /miner login 才会取到新的到期时间")
            self.on_refresh()

        self._run(script, ok)

    def on_passwd(self):
        user = self._current_user()
        if not user:
            return
        password = self.ed_pass.text() or gen_password()
        generated = not self.ed_pass.text()
        hash_ = sha256_hex(user)
        salt = secrets.token_hex(8)
        passhash = sha256_hex(f"{salt}:{password}")
        # 保持原到期时间：远端读出第三段拼回去
        script = (
            f"set -euo pipefail\n"
            f"test -f '{WEB_DIR}/auth/{hash_}.dat' || {{ echo '账号不存在' >&2; exit 1; }}\n"
            f"e=$(awk -F: '{{print $3}}' '{WEB_DIR}/auth/{hash_}.dat')\n"
            f"printf '%s' '{salt}:{passhash}:'\"$e\" > '{WEB_DIR}/auth/{hash_}.dat'\n"
            f"chmod 644 '{WEB_DIR}/auth/{hash_}.dat'\n"
            f"echo \"$e\"\n"
        )

        def ok(out):
            self.log(f"已重置 {user} 的密码，到期时间不变")
            if generated:
                self.log(f"  新密码: {password}  （随机生成，务必现在记下发给用户）")
                QMessageBox.information(
                    self, "密码已重置",
                    f"账号: {user}\n新密码: {password}\n\n密码只显示这一次，请现在复制发给用户。")
            self.ed_pass.clear()

        self._run(script, ok)

    def on_del(self):
        user = self._current_user()
        if not user:
            return
        if QMessageBox.question(self, "确认删除", f"确定删除账号 {user} ？") != QMessageBox.Yes:
            return
        hash_ = sha256_hex(user)
        script = (
            f"rm -f '{WEB_DIR}/auth/{hash_}.dat'\n"
            f"grep -v \"\t{hash_}\t\" '{IDX}' > '{IDX}.tmp' 2>/dev/null || true\n"
            f"mv '{IDX}.tmp' '{IDX}'\n"
        )

        def ok(_out):
            self.log(f"已删除账号 {user}（已登录用户在本地授权到期前仍可用，之后无法再登录）")
            self.on_refresh()

        self._run(script, ok)


def main():
    app = QApplication(sys.argv)
    win = AuthManager()
    win.show()
    win.on_refresh()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()

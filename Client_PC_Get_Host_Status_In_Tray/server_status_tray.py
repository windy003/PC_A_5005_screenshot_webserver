"""
服务器状态托盘监控

从 .env 读取两个完整的状态 API 地址（large / small，各含 http、IP、端口、路径），
在系统托盘显示两个图标，定时轮询各自的接口并把文件数量显示在图标上。

count_api 返回示例（http://192.168.2.56:5005/large/count_api）：
    {"success": true, "site": "large", "path": "", "recursive": false, "count": 3}
"""

import os
import sys
import json
import threading
import webbrowser
import urllib.request
import urllib.parse
from pathlib import Path

from PyQt5.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QAction, QMessageBox
from PyQt5.QtCore import QTimer, pyqtSignal
from PyQt5.QtGui import QIcon, QPixmap, QPainter, QFont, QColor

# 读取同目录下的 .env 配置（缺少 python-dotenv 时静默回退到默认值）
try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).parent / ".env")
except ImportError:
    pass

# API 返回 JSON 中表示文件数量的字段名
COUNT_FIELD = (os.getenv("COUNT_FIELD") or "count").strip()

# 轮询间隔（毫秒）：.env 中的 POLL_INTERVAL 以秒为单位
try:
    POLL_INTERVAL_MS = int(float(os.getenv("POLL_INTERVAL", "60")) * 1000)
except (TypeError, ValueError):
    POLL_INTERVAL_MS = 60000


def create_count_icon(count, bg_color, text=None):
    """创建带有数字（或自定义文字）的托盘图标"""
    pixmap = QPixmap(64, 64)
    pixmap.fill(bg_color)

    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)

    font = QFont("Arial", 32, QFont.Bold)
    painter.setFont(font)

    if text is None:
        text = str(count) if count < 1000 else "999+"

    metrics = painter.fontMetrics()
    text_width = metrics.horizontalAdvance(text)
    text_height = metrics.height()

    text_x = (pixmap.width() - text_width) // 2
    text_y = (pixmap.height() + text_height) // 2 - metrics.descent()

    painter.setPen(QColor(255, 255, 255))
    painter.drawText(text_x, text_y, text)

    painter.end()
    return QIcon(pixmap)


class ServerTrayIcon(QSystemTrayIcon):
    """代表一个站点的托盘图标，定时轮询其完整状态 API 地址"""

    # 后台线程轮询完成后发出: (是否在线, 文件数, 状态消息)
    status_signal = pyqtSignal(bool, int, str)

    def __init__(self, name, url, online_color, parent=None):
        super().__init__(parent)
        self.name = name
        self.url = url
        self.online_color = online_color

        # 从完整地址解析出"打开网页"用的根地址 http://host:port/
        parts = urllib.parse.urlparse(url)
        self.base_url = f"{parts.scheme}://{parts.netloc}/"

        self.online = False
        self.count = 0
        self.last_message = "尚未连接"

        # 跨线程更新界面：信号自动排队到主线程执行
        self.status_signal.connect(self.on_status)

        self.setup_menu()
        self.update_display()
        self.show()

        # 定时轮询
        self.timer = QTimer()
        self.timer.timeout.connect(self.poll)
        self.timer.start(POLL_INTERVAL_MS)

        # 立即轮询一次
        self.poll()

    def setup_menu(self):
        """构建图标的右键菜单"""
        menu = QMenu()

        refresh_action = QAction("刷新状态 (&S)", menu)
        refresh_action.triggered.connect(self.poll)
        menu.addAction(refresh_action)

        open_web_action = QAction("打开网页", menu)
        open_web_action.triggered.connect(self.open_web)
        menu.addAction(open_web_action)

        menu.addSeparator()

        quit_action = QAction("退出 (&X)", menu)
        quit_action.triggered.connect(QApplication.quit)
        menu.addAction(quit_action)

        self.setContextMenu(menu)

    def poll(self):
        """在后台线程发起状态查询，避免阻塞界面"""
        thread = threading.Thread(target=self._poll_worker, daemon=True)
        thread.start()

    def _poll_worker(self):
        try:
            # 绕过系统代理直连：开了代理时局域网 IP 走代理会连接失败
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(self.url, timeout=4) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            count = int(data.get(COUNT_FIELD, 0))
            message = data.get("message", f"在线，共 {count} 个文件")
            self.status_signal.emit(True, count, message)
        except Exception as e:
            self.status_signal.emit(False, 0, str(e))

    def on_status(self, ok, count, message):
        """轮询结果回调（运行在主线程）"""
        self.online = ok
        self.count = count
        self.last_message = message if ok else f"连接失败 ({message})"
        self.update_display()

    def update_display(self):
        """根据当前状态刷新图标和悬浮提示"""
        if self.online:
            icon = create_count_icon(self.count, self.online_color)
        else:
            # 离线：灰色背景 + 问号
            icon = create_count_icon(0, QColor(120, 120, 120), text="?")
        self.setIcon(icon)
        self.setToolTip(self.tooltip_text())

    def tooltip_text(self):
        """悬浮提示：名称、地址、状态"""
        return (
            f"名称: {self.name}\n"
            f"地址: {self.url}\n"
            f"状态: {self.last_message}"
        )

    def open_web(self):
        """在浏览器中打开站点主页"""
        webbrowser.open(self.base_url)


def main():
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)  # 关闭对话框时不退出程序

    if not QSystemTrayIcon.isSystemTrayAvailable():
        QMessageBox.critical(None, "错误", "当前系统不支持系统托盘")
        sys.exit(1)

    # 从 .env 读取两个站点：(名称, 地址, 在线颜色)
    configs = [
        (os.getenv("NAME_LARGE", "大图").strip(),
         (os.getenv("API_LARGE") or "").strip(),
         QColor(0, 120, 215)),    # 大图：蓝色
        (os.getenv("NAME_SMALL", "小图").strip(),
         (os.getenv("API_SMALL") or "").strip(),
         QColor(0, 120, 215)),    # 小图：蓝色
    ]

    icons = []
    for name, url, color in configs:
        if url:
            icons.append(ServerTrayIcon(name, url, color))
        else:
            print(f"未配置 {name} 的 API 地址，跳过")

    if not icons:
        QMessageBox.critical(
            None, "错误",
            "未在 .env 中配置 API_LARGE / API_SMALL，请填写完整的状态 API 地址。"
        )
        sys.exit(1)

    sys.exit(app.exec_())


if __name__ == "__main__":
    main()

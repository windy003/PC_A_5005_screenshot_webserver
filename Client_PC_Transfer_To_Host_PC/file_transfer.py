"""
文件夹监控传送工具
监控 .env 中 Source_Folder 指定的文件夹，一旦里面出现文件，
就把它传送到 .env 中 Target_Folder 指定的文件夹。

Target_Folder 支持局域网 SMB 共享（UNC 路径），例如：
    Target_Folder=\\\\192.168.1.100\\share\\子文件夹
"""

import os
import time
import shutil
import traceback
from pathlib import Path
from datetime import datetime
from dotenv import load_dotenv

# 加载同目录下的 .env 文件
ENV_PATH = Path(__file__).parent / '.env'
load_dotenv(ENV_PATH)

# ===== 配置常量 =====
SCAN_INTERVAL_SECONDS = 5          # 每隔多少秒扫描一次源文件夹
STABLE_WAIT_SECONDS = 1.0          # 判定文件“写入完成”需要大小连续不变的等待间隔
STABLE_RETRIES = 3                 # 连续多少次大小不变才认为文件已写完
TEMP_SUFFIX = ".part"              # 传输过程中目标端使用的临时后缀
MIN_AGE_SECONDS = 60               # 新文件需在源文件夹中存在满多少秒才允许上传

# 记录每个文件首次被发现的时间，用于计算“存在时长”
# key: 文件绝对路径字符串, value: 首次发现的时间戳
first_seen = {}


def log(msg):
    """带时间戳的简单日志"""
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}")


def read_folders():
    """从 .env 读取源 / 目标文件夹（每次循环都重新读，便于中途修改 .env）"""
    # override=True 保证修改 .env 后重新加载能生效
    load_dotenv(ENV_PATH, override=True)
    source = (os.getenv('Source_Folder') or '').strip().strip('"')
    target = (os.getenv('Target_Folder') or '').strip().strip('"')
    return source, target


def is_file_stable(file_path):
    """判断文件是否写入完成：连续多次检测大小不变则认为稳定。
    避免在文件还在被写入 / 复制时就去搬运它。"""
    try:
        last_size = -1
        stable = 0
        while stable < STABLE_RETRIES:
            size = file_path.stat().st_size
            if size == last_size:
                stable += 1
            else:
                stable = 0
                last_size = size
            time.sleep(STABLE_WAIT_SECONDS)
        return True
    except FileNotFoundError:
        # 文件在检测期间被移走/删除
        return False
    except Exception as e:
        log(f"  检测文件稳定性出错 {file_path.name}: {e}")
        return False


def unique_dest(target_dir, name):
    """目标已存在同名文件时，追加时间戳避免覆盖，返回最终目标路径"""
    dest = target_dir / name
    if dest.exists():
        stem = Path(name).stem
        suffix = Path(name).suffix
        dest = target_dir / f"{stem}_{int(time.time())}{suffix}"
    return dest


def transfer_file(file_path, target_dir):
    """把单个文件传送到目标文件夹。
    采用“先复制到 .part 临时文件，成功后改名，再删源文件”的方式，
    避免网络（SMB）中断时目标出现半截文件。"""
    final_dest = unique_dest(target_dir, file_path.name)
    temp_dest = final_dest.with_name(final_dest.name + TEMP_SUFFIX)
    try:
        # 1) 复制到临时文件（保留元数据）
        shutil.copy2(str(file_path), str(temp_dest))
        # 2) 复制完成后在目标端改名为正式文件（同一共享内改名是原子操作）
        os.replace(str(temp_dest), str(final_dest))
        # 3) 目标确认存在后再删除源文件，完成“传送”语义
        if final_dest.exists():
            os.remove(str(file_path))
            log(f"  已传送: {file_path.name} -> {final_dest}")
            return True
        else:
            log(f"  传送异常：目标文件未生成 {final_dest}")
            return False
    except Exception as e:
        log(f"  传送失败 {file_path.name}: {e}")
        # 清理可能残留的临时文件
        try:
            if temp_dest.exists():
                os.remove(str(temp_dest))
        except Exception:
            pass
        return False


def scan_and_transfer(source_dir, target_dir):
    """扫描源文件夹中的所有文件并逐个传送（子文件夹不处理）。
    新发现的文件需存在满 MIN_AGE_SECONDS 后才会被传送。"""
    files = [f for f in source_dir.iterdir() if f.is_file()]

    now = time.time()
    current_paths = {str(f.resolve()) for f in files}

    # 清理已消失文件的记录，避免字典无限增长
    for path in list(first_seen):
        if path not in current_paths:
            del first_seen[path]

    if not files:
        return

    ready = []
    for f in files:
        key = str(f.resolve())
        if key not in first_seen:
            first_seen[key] = now
            log(f"发现新文件，等待存在满 {MIN_AGE_SECONDS} 秒后传送: {f.name}")
        age = now - first_seen[key]
        if age >= MIN_AGE_SECONDS:
            ready.append(f)

    if not ready:
        return

    log(f"{len(ready)} 个文件已满 {MIN_AGE_SECONDS} 秒，准备传送到 {target_dir}")
    for f in ready:
        # 跳过别的程序正在写入、尚未稳定的文件，留到下一轮再处理
        if not is_file_stable(f):
            log(f"  文件未就绪，稍后重试: {f.name}")
            continue
        if transfer_file(f, target_dir):
            # 传送成功后移除记录
            first_seen.pop(str(f.resolve()), None)


def main():
    log("文件夹监控传送工具已启动")
    log(f"配置文件: {ENV_PATH}")

    last_warn = None  # 避免重复刷屏的提示去重

    while True:
        try:
            source, target = read_folders()

            # ----- 校验配置 -----
            if not source or not target:
                msg = "请在 .env 中填写 Source_Folder 和 Target_Folder"
                if msg != last_warn:
                    log(msg)
                    last_warn = msg
                time.sleep(SCAN_INTERVAL_SECONDS)
                continue

            source_dir = Path(source)
            target_dir = Path(target)

            # 源文件夹必须存在
            if not source_dir.exists():
                msg = f"源文件夹不存在: {source_dir}"
                if msg != last_warn:
                    log(msg)
                    last_warn = msg
                time.sleep(SCAN_INTERVAL_SECONDS)
                continue

            # 目标文件夹（含 SMB 共享）：尝试创建/检测可达性
            try:
                target_dir.mkdir(parents=True, exist_ok=True)
            except Exception as e:
                msg = f"目标文件夹不可用（SMB 是否在线 / 是否有写入权限？）: {target_dir} -> {e}"
                if msg != last_warn:
                    log(msg)
                    last_warn = msg
                time.sleep(SCAN_INTERVAL_SECONDS)
                continue

            # 配置正常，清除上一次的告警去重标记
            last_warn = None

            scan_and_transfer(source_dir, target_dir)

        except Exception as e:
            log(f"主循环出错: {e}")
            traceback.print_exc()

        time.sleep(SCAN_INTERVAL_SECONDS)


if __name__ == "__main__":
    main()

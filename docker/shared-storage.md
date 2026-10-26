# GMD-FAQ 共享存储部署指南

本文说明多执行节点部署时如何使用 NFSv4 共享 `.baibaiyu` 目录。共享目录包含租户的 `skills` 和 `workspaces`，因此控制台和所有执行节点看到的是同一份文件，不需要启动 `GMD-FAQ-RUNNER-FILE`。

## 为什么推荐 NFSv4

NFSv4 是 Linux 发行版默认提供的成熟网络文件系统，支持 POSIX 权限、目录锁、挂载恢复和标准备份工具，适合当前按目录读写的 workspace/skill 数据。MinIO 更适合对象存储接口；当前代码大量使用 `Path`、目录遍历、原子重命名和 ZIP 解压，直接改成 MinIO 需要重写文件服务与工具链，不能仅通过 Docker 配置完成。

共享存储只解决文件一致性，不解决 Agent 的内存缓存。多节点请求仍必须携带 `X-GMD-FAQ-Thread-Id`，并在 Nginx upstream 中使用一致性 Hash；runtime 会通过 Redis 版本通知淘汰其他节点的 Agent 缓存。

## NFS 服务端

以下命令在一台稳定的 Linux 主机执行。示例共享目录为 `/srv/gmd-faq-data`，生产环境请改成独立磁盘并纳入备份。

### Debian/Ubuntu

```bash
sudo apt update
sudo apt install -y nfs-kernel-server
sudo mkdir -p /srv/gmd-faq-data
sudo chown -R 1000:1000 /srv/gmd-faq-data
```

### RHEL/CentOS/Rocky

```bash
sudo dnf install -y nfs-utils
sudo mkdir -p /srv/gmd-faq-data
sudo chown -R 1000:1000 /srv/gmd-faq-data
```

在 `/etc/exports` 增加执行节点和控制台节点的实际网段。以下示例只允许内网读写，并启用 root 映射保护：

```text
/srv/gmd-faq-data 10.10.0.0/24(rw,sync,root_squash,no_subtree_check)
```

应用配置并启动服务：

```bash
sudo exportfs -rav
sudo systemctl enable --now nfs-server   # Debian/Ubuntu 也可使用 nfs-kernel-server
```

NFSv4 通常只需放行 TCP `2049`，如果系统启用了 rpcbind 或旧客户端，还需要 TCP/UDP `111`。防火墙必须只对可信内网开放；不要将 NFS 端口暴露到公网。

## 客户端挂载

在 console 主机和每一个 execute 主机安装客户端：

```bash
# Debian/Ubuntu
sudo apt install -y nfs-common

# RHEL/CentOS/Rocky
sudo dnf install -y nfs-utils
```

挂载并验证：

```bash
sudo mkdir -p /data/gmd-faq
sudo mount -t nfs4 -o rw,hard,timeo=600,retrans=2 nfs-server:/srv/gmd-faq-data /data/gmd-faq
mount | grep /data/gmd-faq
touch /data/gmd-faq/.nfs-write-test && rm /data/gmd-faq/.nfs-write-test
```

建议写入 `/etc/fstab`，使节点重启后自动恢复挂载：

```text
nfs-server:/srv/gmd-faq-data /data/gmd-faq nfs4 rw,hard,_netdev,noatime 0 0
```

## Docker 配置

console 和 execute 主机都将 `DATA_PATH` 指向挂载点的上一级目录，使容器内 `/app/.baibaiyu` 对应同一个 NFS 目录：

```dotenv
DATA_PATH=/data/gmd-faq
```

执行节点使用共享存储模式：

```dotenv
GMD_FAQ_STORAGE_MODE=shared
```

此模式下执行 `bash start-execute.sh build` 不会启动 `GMD-FAQ-RUNNER-FILE`。console 节点启动 `GMD-FAQ-RUNNER-CONSOLE`、`GMD-FAQ-RUNNER-WEBSOCKET` 和前端；每个 execute 节点启动 `GMD-FAQ-RUNNER-RUNTIME`、`GMD-FAQ-RUNNER-PROXY`、`GMD-FAQ-RUNNER-GATEWAY`。不要让同一套数据同时使用 NFS 和本地 workspace 同步模式，否则会出现两个数据源互相覆盖。

## 工具图片存储

工具和 MCP 返回的图片副本保存在会话 workspace 的 `.tool-results/` 目录，使用调用标识与文件内容摘要定位；它们随会话文件一起共享、备份和清理。控制台沿用会话文件权限校验后提供预览和下载，不需要新增公开文件服务。图片保存受 `WORKSPACE_CAPACITY_MB` 限制，单张最多 10 MB，单次工具返回最多处理 20 张。

远程图片默认只下载公网 HTTP/HTTPS 地址。如果工具返回企业内网图片服务地址，在执行节点的 `.env` 中配置精确主机名，例如：

```dotenv
TOOL_IMAGE_ALLOWED_HOSTS=images.internal.example.com
```

多个主机用逗号分隔；重定向的目标主机也必须符合网络限制。下载不会转发用户 token 或其他登录凭据。单机和分布式执行节点的 Compose 配置均会将该变量传给 runtime。

## 权限与安全

容器默认以应用用户写入 `.baibaiyu`，服务端目录的 UID/GID 必须与容器实际用户匹配。`root_squash` 是默认推荐值；只有在明确验证容器需要 root 写入且网络隔离充分时，才考虑 `no_root_squash`，否则远程 root 可以修改共享目录中的任意文件。应限制导出网段、禁止公网访问，并为 NFS 主机配置磁盘配额。

## 备份与故障处理

共享目录必须纳入定期快照或备份，至少备份 `.baibaiyu/tenants/*/skills` 和 `.baibaiyu/tenants/*/workspaces`。NFS 暂时不可用时，`hard` 挂载会等待服务恢复，避免静默写入错误位置；恢复后用 `mount`、`df -h` 和写入测试确认挂载仍然有效。不要在未挂载时直接启动生产容器，否则 Docker 可能在本地目录创建同名路径，造成数据分叉。

## 本地存储模式

无法提供 NFS 时，可以在每个 execute 节点配置：

```dotenv
GMD_FAQ_STORAGE_MODE=local
DATA_PATH=/data/gmd-faq-node-01
```

启动脚本会启用 `GMD-FAQ-RUNNER-FILE`。Agent 流结束后，runtime 将 workspace 放入本地队列，30 秒内持续变化会合并，静默 30 秒后上传到 console；console 原子落盘并通过 Redis 通知其他节点，节点启动时还会主动补齐已有快照。该方案依赖所有节点共享同一个 MySQL、Redis，并正确配置 `CONSOLE_HOST`。节点故障期间正在运行的 Agent 和尚未上传的本地变化无法做到无损恢复，生产环境优先使用 NFS。

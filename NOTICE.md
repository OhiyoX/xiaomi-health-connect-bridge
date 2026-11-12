# NOTICE

本仓代码为本项目自有（MIT，见 `LICENSE`），但**协议实现依据公开的第三方逆向成果**，
在此声明来源与许可（均 MIT）：

## 小米健康云（hlth.io.mi.com）

- **binglua/mi-fitness-mcp-cn** — <https://github.com/binglua/mi-fitness-mcp-cn>
  - 采用：健康数据端点 `POST /app/v1/data/get_fitness_data_by_time`、`key=heart_rate` 等键名、
    RC4 加解密与 `rc4_hash__` / `signature` 双签名算法、`serviceLogin` + cookie 认证流程、
    `ssecurity`/`nonce` 推导方式。
  - 对应实现：`app/src/main/java/id/harness/hcbridge/CloudApi.kt`，
    以及 Termux 侧的 `tools/hc-cloud/hc-cloud.py`。
- **Misty02600/mi-fitness-python**（`mi-fitness` SDK） — <https://github.com/Misty02600/mi-fitness-python>
  - 参考：二维码登录（`longPolling/loginUrl`）、数据模型字段命名。
- **xiaomi-health-exporter**（WElArfaoui） — 仅作为"官方 GDPR 导出"兜底路径的调研参考，未使用其代码。

## 小米健康云开放平台文档

- <https://dev.mi.com/docs/micloud/health/> —— 数据点 / 数据类型 / 数据源模型说明（公开文档）。

## 免责

- 本项目与小米公司无关，仅用于读取**自己账号**的健康数据。
- 所用端点为非官方逆向社会的结果，**可能随小米改版失效**；不保证可用性。
- 不修改小米应用、不绕过其账号或设备绑定。

# SeedShield

**Paper/Spigot 服务器的加密结构种子保护插件。**

**中文 | [English](README_EN.md)**

SeedShield 通过将每种结构类型的放置盐值（salt）替换为不可逆的 SHA-256 哈希值，防止种子破解工具（chunkbase、SeedCrackerX、Structurecracker）定位结构位置。

**这是第一个为 Paper/Spigot 提供加密级结构种子保护的插件。** 此前，这种级别的保护只能通过 Fabric mod 或自定义服务端 fork 才能实现。

## 工作原理

Minecraft 通过以下公式确定结构位置：

```
位置 = f(世界种子, 区域坐标, 盐值)
```

每种结构类型的 `盐值（salt）` 是硬编码的整数。chunkbase 等工具已知这些默认盐值，因此知道世界种子 = 知道所有结构位置。

SeedShield 将每个盐值替换为：

```
盐值 = SHA-256(密钥 + ":" + 世界种子 + ":" + 结构类型名)[前4字节]
```

- **结构间隔离**：每种结构类型获得独立的加密盐值。破解一种结构的盐值不会泄露其他结构的信息。
- **密钥保护**：没有 256 位密钥（存储在 `config.yml` 中），盐值无法被逆向。
- **要塞保护**：同时修改 `concentricRingsSeed` 并重新计算环形位置。

## 与现有方案对比

| 方案 | 平台 | 修改结构位置 | 加密保护 | 结构间隔离 | 要塞保护 |
|------|------|:---:|:---:|:---:|:---:|
| **SeedShield** | **Paper/Spigot 插件** | **✅** | **✅ SHA-256** | **✅** | **✅** |
| [SeedGuard](https://github.com/DrexHD/SeedGuard) | Fabric mod | ✅ | ❌ 随机数 | ✅ | ✅ |
| [SecureSeed](https://github.com/Earthcomputer/SecureSeed) | Fabric mod（1.16.5，已停更） | ✅ | ✅ BLAKE2 | ✅ | ✅ |
| [AntiSeedCracker](https://github.com/akshualy/AntiSeedCracker) | Spigot 插件 | ❌ | ❌ | ❌ | ❌ |
| Leaf `secure-seed` | 服务端 fork | ✅ | ✅ 1024位 | ✅ | ✅ |
| spigot.yml 种子配置 | 内置功能 | ✅ | ❌ 明文整数 | ❌ | ⚠️ |

## 安装方法

1. 从 [Releases](https://github.com/7yunluo/SeedShield/releases) 下载 `SeedShield-1.0.0.jar`
2. 放入服务器的 `plugins/` 文件夹
3. **删除需要保护的世界的 region 文件**（结构需要重新生成）
4. 重启服务器
5. 编辑 `plugins/SeedShield/config.yml` 配置需要保护的世界

> **注意**：SeedShield 仅影响新生成的区块。已生成区块中的结构位置不会改变。

## 配置文件

```yaml
# 自动生成的 256 位密钥，请勿泄露。
secret-key: "a1b2c3d4..."

# 需要保护的世界列表
enabled-worlds:
  - world
  - survival
```

## 环境要求

- Paper 1.21+（或其 fork：Leaves、Purpur、Folia 等）
- Java 17+

## 安全性分析

| 攻击方式 | 防护等级 |
|---------|:---:|
| chunkbase / 在线种子地图 | ✅ 完全失效 |
| SeedCrackerX 客户端 mod | ✅ 已防御（建议配合 FakeSeed 使用） |
| 暴力破解单种结构盐值（2³² 次尝试） | ⚠️ 单种结构可能被破解，但每种结构需独立破解 |
| 从盐值反推密钥 | ✅ 不可行（SHA-256 抗原像攻击） |
| 跨结构推导盐值 | ✅ 没有密钥不可能实现 |

## 从源码构建

```bash
git clone https://github.com/7yunluo/SeedShield.git
cd SeedShield
mvn package
```

构建产物位于 `target/SeedShield-1.0.0.jar`。

## 许可证

MIT License

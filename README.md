# contract-abi-codec-java

面向合约函数调用的**规范 ABI 编解码服务**。根据注册的函数定义对入参做规范二进制编码、
对返回结果做严格解码，并提供函数选择器碰撞检测与诊断。

## 功能概览

- **类型系统**：标量（`uintN`/`intN`/`bool`/`address`）、定长字节（`bytesN`）、
  动态字节串（`bytes`）、字符串（`string`）、动态数组（`T[]`）、静态数组（`T[k]`）
  与任意嵌套元组（`(T1,...,Tn)`）。
- **严格规范编码**：编码只产生规范表示；解码只接受规范表示，任何非规范输入都被拒绝。
- **选择器管理**：按 `keccak256(规范签名)[0..4]` 计算选择器；注册时识别重复与碰撞，
  碰撞诊断稳定（签名排序固定），并保留 alias 手动消歧入口。
- **结构化错误**：所有失败以携带错误码与字节偏移的 `AbiException` 抛出，绝不返回部分结果。
- **并发安全**：注册表写操作原子生效，执行中的调用基于不可变一致快照。
- **有界缓存**：类型布局与选择器缓存带容量上限（LRU 淘汰），键携带注册表版本号，
  定义更新后旧缓存自然失效。
- **崩溃安全持久化**：临时文件 + fsync + 原子 rename + CRC32 校验，崩溃不会留下半写入状态。
- **资源上限**：单次调用输入字节数与嵌套深度均有明确上限，超限快速失败。

## 规范约束（严格模式）

解码器对每个值只接受规范表示，以下情况一律拒绝：

| 约束 | 错误码 |
| --- | --- |
| 动态成员偏移未精确指向尾部当前位置（非最小编码） | `NON_CANONICAL` |
| 偏移未按 32 字节对齐 | `NON_CANONICAL` |
| `bytesN` / 动态字节尾部填充字节非零 | `NON_CANONICAL` |
| `uintN` 高位（256-N 位）非零 | `NON_CANONICAL` |
| `intN` 未正确符号扩展 | `NON_CANONICAL` |
| `bool` 字不为 0 或 1 | `NON_CANONICAL` |
| `address` 高 12 字节非零 | `NON_CANONICAL` |
| `string` 内容不是合法 UTF-8 | `NON_CANONICAL` |
| 长度前缀超过缓冲区可承载范围 | `OUT_OF_BOUNDS` |
| 读取越过缓冲区末尾（截断输入） | `OUT_OF_BOUNDS` |
| 解码完成后仍有未消费字节 | `TRAILING_BYTES` |
| 编码值超出类型范围（如 `uint8` 赋 256） | `INTEGER_OVERFLOW` |
| 定长字节值超长需截断 | `TRUNCATION` |
| 值 Java 类型与 ABI 类型不符 / 参数个数不符 | `TYPE_MISMATCH` |
| 类型或值嵌套超过深度上限 | `DEPTH_LIMIT_EXCEEDED` |
| 输入字节数超过上限 | `INPUT_LIMIT_EXCEEDED` |
| 选择器对应多个函数且未指定 alias | `SELECTOR_COLLISION` |
| 选择器/别名/签名未注册 | `UNKNOWN_SELECTOR` |

类型字符串也必须是规范形式：不允许裸 `uint`/`int`（必须显式位宽，8..256 且为 8 的倍数），
`bytesN` 长度须在 1..32，静态数组长度须为正整数，不允许空元组。

## 值模型（Java 侧）

| ABI 类型 | 接受的 Java 值 | 解码返回 |
| --- | --- | --- |
| `uintN` / `intN` | `BigInteger` / 整数包装类型 / 十进制或 `0x` 十六进制字符串 | `BigInteger` |
| `bool` | `Boolean` | `Boolean` |
| `address` | `byte[20]` / `0x`+40 位 hex / 整数 | `byte[20]` |
| `bytesN` / `bytes` | `byte[]` / `0x` hex 字符串 | `byte[]` |
| `string` | `String`（UTF-8） | `String` |
| `T[]` / `T[k]` / 元组 | `List` | `List` |

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `codec.max-input-bytes` | `65536` | 单次调用输入字节数上限（含编码产物） |
| `codec.max-depth` | `32` | 类型/值嵌套深度上限 |
| `codec.cache-capacity` | `1024` | 布局与选择器缓存容量上限（条目数，LRU 淘汰） |
| `codec.store-file` | `data/registry.bin` | 注册表持久化文件路径 |
| `codec.persist-on-change` | `true` | 注册表变更时是否自动持久化 |

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/functions` | 注册函数 `{name, inputs, outputs, alias?}`，返回含选择器的定义 |
| `GET` | `/api/functions` | 列出全部已注册函数 |
| `GET` | `/api/functions/collisions` | 选择器碰撞诊断报告 |
| `POST` | `/api/encode` | `{function, args}` → `{calldata}`（alias 或规范签名寻址） |
| `POST` | `/api/decode-call` | `{calldata, alias?}` → 参数列表 |
| `POST` | `/api/decode-result` | `{function, data}` → 返回值列表 |
| `GET` | `/api/cache/stats` | 缓存占用统计 |

错误响应统一为 `400 {"code": "...", "offset": n, "message": "..."}`。

### 示例

```bash
curl -X POST localhost:8080/api/functions -H 'Content-Type: application/json' -d '{
  "name": "transfer", "inputs": ["address", "uint256"], "outputs": ["bool"]}'

curl -X POST localhost:8080/api/encode -H 'Content-Type: application/json' -d '{
  "function": "transfer",
  "args": ["0x0000000000000000000000000000000000000001", "1000"]}'
# => {"calldata":"0xa9059cbb0000...00010000...03e8"}
```

## 选择器碰撞处理

选择器为 4 字节，碰撞不可避免。注册表按选择器分组诊断：

- `GET /api/functions/collisions` 返回所有碰撞组（选择器 + 排序后的签名列表，输出稳定）。
- 注册时可提供 `alias`（默认取函数名，全局唯一）作为手动消歧入口。
- 调用编解码时若选择器有碰撞且未指定 alias，返回 `SELECTOR_COLLISION` 并列出候选签名；
  指定 alias 即可正常调用。

## 持久化与崩溃恢复

- 写入协议：序列化 → 写 `<store>.tmp` → fsync → 原子 rename → fsync 父目录。
- 崩溃时正式文件要么保持旧版本要么完整切换，残留的 `.tmp` 不参与加载。
- 文件带魔数、格式版本与 CRC32 校验；损坏时抛 `PERSISTENCE_CORRUPT`，不返回截断数据。
- 选择器不落盘，恢复时按规范签名重新计算，避免信任存储内容。
- 服务启动时自动 `recover()` 恢复注册表。

## 并发与缓存语义

- `FunctionRegistry` 写操作（注册/移除/替换）在内部锁内原子完成并递增版本号。
- 每次调用从 `snapshot()` 获取不可变一致快照，执行中不会观察到注册到一半的定义。
- 缓存键 = (条目种类, 注册表版本, 主题)，版本递增后旧条目不再命中并随 LRU 淘汰，
  因此定义更新后不会返回旧布局，长期运行也不会无界增长。

## 构建与测试

```bash
mvn test        # 运行全部测试（52 个）
mvn spring-boot:run
```

测试覆盖：嵌套动态类型往返、非规范编码拒绝（尾零/短长度/错位偏移/符号扩展等）、
选择器碰撞（真实碰撞对 `f38491(uint256)` / `f116643(uint256)` → `0x77dbd42e`）、
并发注册与并发编解码、崩溃恢复（半写入 tmp / 校验和损坏 / 截断文件）、
资源上限（输入长度 / 嵌套深度 / 缓存容量）与 HTTP 端到端。

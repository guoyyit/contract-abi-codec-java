# contract-abi-codec-java

面向合约函数调用的**规范 ABI 编解码服务**。服务根据注册的函数定义对入参做规范二进制编码、
对返回结果做严格解码，并提供函数选择器碰撞检测与诊断。编码结果严格符合规范；任何非规范
表示都会被拒绝，且解码失败时绝不返回部分结果。

## 功能总览

| 能力 | 说明 |
| --- | --- |
| 类型系统 | 标量（`uintN`/`intN`/`address`/`bool`）、定长字节（`bytesN`）、动态字节串（`bytes`）、字符串（`string`）、动态数组（`T[]`）、静态数组（`T[k]`）、嵌套元组（`(T1,T2,...)`） |
| 规范编码 | 每个值只接受唯一规范表示；越界、溢出、截断、错误长度一律拒绝 |
| 严格解码 | 拒绝非规范尾零、非规范符号扩展、越界偏移、重叠/乱序偏移、尾随字节、无效 UTF-8 |
| 选择器 | 基于 Keccak-256 的规范签名选择器；注册时检测重复与碰撞，诊断结果稳定（按字典序排序） |
| 手动消歧 | `assignAlias(alias, signature)` 为碰撞选择器提供确定性入口 |
| 并发 | 注册与编解码均可并发；每次调用基于一致的不可变快照，绝不观察到注册到一半的状态 |
| 缓存 | 类型布局使用有界 LRU 缓存；函数定义更新后整体失效，不会返回旧布局，不会无界增长 |
| 持久化 | 临时文件 + fsync + 原子 rename + SHA-256 完整性校验；任何读写故障立即失败且内存/磁盘零痕迹，旧完整状态始终可恢复 |
| 资源上限 | 单次调用输入长度、嵌套深度均有明确上限，超限快速失败且不影响其他请求 |

## 规范约束（Canonical Encoding）

### 编码侧（值必须满足）

- `uintN`：非负整数，且 `< 2^N`；负数与溢出拒绝（`INTEGER_OVERFLOW`）。
- `intN`：`-2^(N-1) <= v < 2^(N-1)`；越界拒绝。
- `address`：恰好 20 字节（`0x` 十六进制字符串或 `byte[20]`）。
- `bool`：仅接受 Java/JSON 布尔值（数字 `0/1` 被拒绝，`TYPE_MISMATCH`）。
- `bytesN`：恰好 N 字节。
- `bytes`/`string`：字节数组/`0x` 十六进制字符串/Java String（UTF-8）。
- 数组/元组：位置式 `List`；静态数组长度必须精确匹配。

### 解码侧（以下全部拒绝，抛结构化错误，不返回部分结果）

| 非规范形态 | 错误码 |
| --- | --- |
| 整数高位填充非零（`uintN`）/ 非规范符号扩展（`intN`） | `INTEGER_TRUNCATION` |
| `bool` 字非 0/1 或填充非零 | `TYPE_MISMATCH` / `NON_CANONICAL_ENCODING` |
| `address` 高 12 字节非零 | `NON_CANONICAL_ENCODING` |
| `bytesN` 尾部非零 | `NON_CANONICAL_ENCODING` |
| 动态数据 padding 非零 | `NON_CANONICAL_ENCODING` |
| 偏移指向 head 区内 / 重叠 / 乱序 / 非紧凑（有间隙） | `NON_CANONICAL_ENCODING` |
| 读取越界（短长度、截断、长度前缀越界） | `OUT_OF_BOUNDS_READ` |
| 解码后存在未消费字节 | `TRAILING_BYTES` |
| `string` 非合法 UTF-8 | `TYPE_MISMATCH` |
| 嵌套深度超限 | `DEPTH_EXCEEDED` |
| 输入超过大小上限 | `INPUT_TOO_LARGE` |

所有错误通过 `CodecException`（携带 `ErrorCode` 与可选 JSON 路径 `path`）抛出；
HTTP 层统一返回 `{"code","path","message"}`。

## 选择器与碰撞

- 选择器 = `keccak256(canonicalSignature)[0..4]`，规范签名形如 `transfer(address,uint256)`。
- 同一规范签名重复注册是幂等 upsert，不产生碰撞。
- 不同签名产生相同 4 字节选择器时：
  - `GET /api/codec/collisions` 返回稳定排序的诊断（选择器 + 字典序签名列表）；
  - 通过选择器解析会抛出 `SELECTOR_COLLISION`（HTTP 409）；
  - `POST /api/codec/aliases {"alias":"...","signature":"..."}` 手动消歧后，可用别名解析。

## 并发与一致性

- 注册表内部维护**不可变快照**（`RegistrySnapshot`），所有写操作在排他锁下原子替换快照。
- 编解码调用先取一次快照，整个调用期间使用该快照，因此**永远不会观察到注册到一半的定义**。
- 批量注册（`registerAll`）是原子的：要么全部可见，要么全部不可见。

## 缓存

- `TypeLayoutCache` 为**有界 LRU**（容量可配），键为规范类型串。
- 任何函数定义变更都会触发整体失效，保证不会返回旧布局。
- 缓存大小永远不超过容量上限，长期运行无无界增长。

## 持久化与崩溃恢复

### 故障原子性保证

- 写入流程：先在内存中构造候选状态 → 完整写入 `<file>.tmp` → `force(true)`
  刷盘（数据与元数据）→ 原子 `rename` 覆盖目标文件。
- 创建/写入/刷盘/替换任一步骤发生读写故障（磁盘写满、配额耗尽、目录只读、
  替换失败等）都会立即以 `PERSISTENCE_ERROR` 失败，**绝不返回成功**；
  故障期间删除残留临时文件，旧目标文件保持不变。
- 注册采用“**先落盘、后改内存**”顺序：只有持久化成功后才替换内存快照，
  因此失败的注册在内存与磁盘上都**不留任何可观察痕迹**，后续查询、
  后续成功注册的快照都不会包含那次失败定义；批量注册同样全有或全无。
- 崩溃在任何阶段都只会留下**完整的旧文件或完整的新文件**；
  残留的 `.tmp`（崩溃于 rename 前）在下次启动时被忽略并清理，
  临时文件以 `TRUNCATE_EXISTING` 方式重建，因此既不会被当作有效状态加载，
  也不会阻塞故障解除后的下一次注册。
- 一次失败写入后立刻重启，故障前已注册的函数完整可用；
  故障解除后的下一次注册成功并完整落盘。
- 并发注册由注册表串行化；其中一次因存储故障失败不影响同批次/其他并发注册，
  成功注册的结果可查询且可重启恢复。

### 文件格式、兼容性与完整性验证

| 字段 | 说明 |
| --- | --- |
| `version` | 文件格式版本；当前写入为 `2` |
| `functions` | 完整函数定义数组（名称、输入、输出及规范类型） |
| `checksum` | 对 `functions` 数组紧凑（无缩进）序列化字节计算的小写 SHA-256 |

- 新加载流程必须同时满足：是合法 JSON、结构完整、`checksum` 与重算值一致。
  任何没有完整落盘的内容（截断、被部分覆盖）即使碰巧是合法 JSON，
  也以 `CORRUPTED_STATE` 显式报错，**绝不静默清空注册表**。
- 上一版本的文件（`version: 1`，或无 `version` 字段、无 `checksum`）
  **无需迁移即可继续加载**；下一次成功写入时自动升级为版本 2。
  因此旧格式兼容与新格式完整性校验互不冲突。
- 校验方法（可手工复现）：解析文件取 `functions` 节点，使用 Jackson
  默认（无 `INDENT_OUTPUT`）配置序列化为字节，计算 SHA-256 小写十六进制，
  与顶层 `checksum` 做常量时间比较即可。

### 损坏状态与读写故障的区分

| 错误码 | 触发条件 | HTTP 状态 |
| --- | --- | --- |
| `PERSISTENCE_ERROR` | 存储层读写/刷盘/替换/读取文件失败（磁盘满、配额、只读目录、IO 错误） | `500` |
| `CORRUPTED_STATE` | 文件可访问但内容未完整落盘：非法 JSON、结构缺失、校验和缺失/不匹配 | `500` |
| 调用方输入类错误（`INVALID_SIGNATURE`、`OUT_OF_BOUNDS_READ`、`NON_CANONICAL_ENCODING` 等） | 参数/编码本身不合法 | `400`（或 404/409/413 等对应状态） |

进程内接口与 HTTP 接口均保持这一区分：存储侧问题永远是服务器错误，
不会被误报为调用方参数错误。

### 解码边界判定规则

- 动态数据（`bytes`/`string`/动态数组/动态元组）的读取严格以其**所属编码块
  末端**为界，而不是以整个输入缓冲区长度为界；内层数据不得读到同层其他成员
  （兄弟 tail）或块外的任何字节。
- 声明长度与补齐恰好填满所属块末端时**成功解码**；少一个载荷字节或少一个
  补齐字节均为 `OUT_OF_BOUNDS_READ`，不会误报为 `NON_CANONICAL_ENCODING`，
  也不会返回部分结果。仅当结构完整但补齐字节非零时才是
  `NON_CANONICAL_ENCODING`。
- 同一截断形态在任意嵌套层级（顶层参数、元组、多层嵌套元组、定长/动态数组）
  都报告同一错误码 `OUT_OF_BOUNDS_READ`，错误 `path` 指向真正越界的参数
  （如 `$[0][0][0]`）。
- 越界错误信息统一使用**绝对字节偏移**与**实际输入长度/剩余字节数**，
  单位均为字节，不混用相对偏移、字数（word）或块内剩余长度。

## 配置项（`codec.*`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `codec.max-input-bytes` | `1048576` | 单次调用输入/输出编码的最大字节数 |
| `codec.max-nesting-depth` | `32` | 元组/数组最大嵌套深度 |
| `codec.layout-cache-capacity` | `1024` | 类型布局 LRU 缓存容量 |
| `codec.registry-file` | `data/registry.json` | 注册表持久化文件 |
| `codec.persistence-enabled` | `true` | `false` 时使用纯内存注册表 |

## 本地进程内调用

```java
CodecApi api = ...; // Spring 注入

FunctionDefinition transfer = api.registerFunction(new FunctionDefinition(
        "transfer",
        List.of(new Parameter("to", new AbiType.Address()),
                new Parameter("amount", new AbiType.UInt(256))),
        List.of(new Parameter("ok", new AbiType.Bool()))));

byte[] calldata = api.encode("transfer", List.of(
        "0x1122334455667788990011223344556677889900",
        BigInteger.valueOf(12345)));
// calldata = 0xa9059cbb0000000000000000000000001122...99000000...3039

List<Object> outputs = api.decode("transfer", returnData);
```

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/codec/functions` | 注册函数定义 |
| `POST` | `/api/codec/functions/batch` | 原子批量注册 |
| `GET` | `/api/codec/functions/{ref}` | 按名称/签名/选择器/别名查询 |
| `POST` | `/api/codec/functions/{ref}/encode` | 编码调用（返回 `0x` calldata） |
| `POST` | `/api/codec/functions/{ref}/decode` | 解码返回值（或带选择器的 calldata） |
| `POST` | `/api/codec/functions/{ref}/decode-args` | 解码调用参数 |
| `GET` | `/api/codec/collisions` | 选择器碰撞诊断 |
| `POST` | `/api/codec/aliases` | 手动消歧 |

## 构建与测试

```bash
mvn test
```

测试覆盖：嵌套动态类型往返、非规范编码拒绝（尾零/短长度/乱序偏移/截断）、
选择器碰撞与手动消歧、并发注册与快照一致性、崩溃恢复（残留 tmp / 损坏文件）、
资源上限（输入大小、嵌套深度、缓存容量与失效）以及 HTTP 集成。

故障原子性相关测试仅依赖系统能力与仓库已有依赖：

- `StorageFaultAtomicityTest`：在创建、写入、刷盘、原子替换各阶段注入故障
  （包级可见的测试扩展点），并使用只读目录制造真实读写故障，覆盖故障码分类、
  内存/磁盘零痕迹、重启恢复、临时文件残留不阻塞、校验和、截断/篡改检测、
  v1/无版本旧文件兼容、并发注册隔离与批量全有或全无；
- `DecodeBoundaryTest`：动态数据恰好触及块末端、少一个载荷/补齐字节、
  多层嵌套边界、内层不得读取兄弟数据、同一截断形态跨层级一致分类、
  错误路径与错误信息单位；
- `HttpErrorMappingTest`：存储故障与输入错误的 HTTP 状态码区分。

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
| 持久化 | JSON 信封 + SHA-256 完整性校验 + 临时文件 + 原子 rename + fsync；任何写/刷盘/替换故障下注册全有或全无，崩溃不留下半写入状态，恢复后注册表完整可用 |
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

- 写入流程：内存序列化全部状态 → 写入唯一临时文件 `<file>.tmp` → `fsync` 刷盘
  → 原子 `rename` 覆盖目标文件 → 目录 fsync。
- 写入、刷盘、替换中的**任何 I/O 故障**（磁盘写满、配额耗尽、目录只读、替换失败等）
  都会让该次注册立即以 `PERSISTENCE_ERROR` 失败：不会返回成功，也不会把部分内容
  当作最新状态。
- 失败是**全有或全无**的：
  - 目标文件始终未被触碰（临时文件先写后改名），磁盘状态与调用前完全一致；
  - 内存中的函数集合在捕获到持久化失败后回滚到调用前快照，失败的函数既不可查询，
    也不会出现在之后任何一次成功注册的快照中；
  - 临时文件在失败后立即清理。
- 崩溃在任何阶段都只会留下**完整的旧文件或完整的新文件**；残留的 `.tmp`
  （崩溃于 rename 前，即使内容恰好是合法 JSON）在下次启动时被忽略并清理，
  不会被当作有效状态加载，也不会阻塞后续注册。故障解除后的下一次注册即可成功落盘。

### 损坏状态与读写故障的区分

| 情形 | 错误码 | 含义 |
| --- | --- | --- |
| 写入 / 刷盘 / 替换过程中的 I/O 故障 | `PERSISTENCE_ERROR` | 本次写入失败，旧状态完好；调用方可重试 |
| 磁盘上的状态无法通过完整性验证 | `CORRUPTED_STATE` | 现存状态文件不可信，需要人工介入，不得静默清空 |
| 调用方输入不合法（坏类型、坏参数等） | `INVALID_SIGNATURE` 等 | 4xx 类输入错误，与存储故障严格区分 |

- HTTP 层：`PERSISTENCE_ERROR` 与 `CORRUPTED_STATE` 返回 **500**，调用方输入错误返回
  **4xx**（`NOT_FOUND` 404、`SELECTOR_COLLISION` 409、超大输入 413 等）。
- 进程内接口与 HTTP 接口使用同一套 `ErrorCode`，二者分类一致。

### 文件格式、兼容性与完整性验证

- **v2（当前写入格式）**：外层信封为
  `{"version":2,"algorithm":"SHA-256","checksum":"<hex>","payload":"<转义后的状态JSON>"}`。
  `checksum` 是 `payload` 原始字节的 SHA-256（小写十六进制），加载时逐字节重算比对。
- **v1（上一版本格式）**：即 `{"version":1,"functions":[...]}`，无校验和字段。
  v1 文件**无需迁移即可继续加载**；加载后第一次成功写入会自动升级为 v2。
  无校验和意味着 v1 只做结构合法性验证，校验和验证仅对新写入的 v2 内容强制生效，二者不冲突。
- 加载验证规则：
  - 文件不可读 / 非合法 JSON / 非 JSON 对象 → `CORRUPTED_STATE`；
  - v2 缺少 `checksum` / `algorithm` / `payload` 任一字段，或算法不支持 → `CORRUPTED_STATE`；
  - **即使内容能被解析为合法 JSON，只要校验和不匹配（例如未完整落盘的写入），一律
    `CORRUPTED_STATE`**，绝不静默接受为空注册表或部分注册表；
  - 条目结构缺失、类型串无法解析 → `CORRUPTED_STATE`。
- 手动验证文件完整性：
  ```bash
  # 提取 payload（JSON 转义文本）并重算 SHA-256，应与 checksum 字段一致
  python3 -c '
  import json,hashlib,sys
  env=json.load(open(sys.argv[1]))
  p=env["payload"].encode("utf-8")
  print(hashlib.sha256(p).hexdigest(), env["checksum"])'
  ```

## 解码边界判定规则

- **块（block）**：参数列表、元组或数组元素各自占据一个连续区域，有明确的起点与末端。
  一切读取都以**所属块的末端**为界，而不是以整个输入缓冲的末端为界；越过所属块边界
  哪怕一个字节，也不会读到同层兄弟数据或块外数据。
- 动态值（`bytes`/`string`/动态数组/动态元组）的**声明长度 + 右填充**恰好填满所属块
  末端时，属于合法编码，必须成功解码。
- 少一个载荷字节、填充不完整、长度前缀越界、偏移字或定长头槽落在块外 →
  `OUT_OF_BOUNDS_READ`（不会误报为 `NON_CANONICAL_ENCODING`，也不会返回部分结果）。
- 仅在“字节数足够但布局不符合唯一规范表示”时才报 `NON_CANONICAL_ENCODING`：
  偏移指向 head 区、偏移非严格递增、动态尾部非紧凑排列、padding 非零等。
- 错误信息中的偏移与剩余长度**统一使用字节为单位**（绝对字节偏移 + 所属块末端剩余字节），
  不混用字（word）与字节。
- 同一种截断形态无论嵌套多少层，都以同一个错误码（`OUT_OF_BOUNDS_READ`）报告；
  `path` 指向真正出错的参数，例如顶层 `$[0]`、两层嵌套 `$[0][0]`、三层 `$[0][0][0]`。

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
资源上限（输入大小、嵌套深度、缓存容量与失效）、HTTP 集成，以及：

- **故障原子性**：在写入 / fsync / 替换各阶段注入 I/O 故障，验证注册以
  `PERSISTENCE_ERROR` 失败、内存与磁盘零痕迹、失败后立即重启可恢复到故障前完整状态、
  临时文件不被加载也不阻塞后续注册、并发注册相互隔离、HTTP 层存储故障返回 500；
- **完整性验证**：校验和不匹配、信封字段缺失、恰好是合法 JSON 的撕裂写入一律
  `CORRUPTED_STATE`，v1 文件无需迁移可读、写入后自动升级为 v2；
- **解码边界**：动态数据声明长度 + 填充恰好触及块末端成功，少一个字节 / 填充缺失 /
  内层数据越过外层块边界 / 多层嵌套截断一律 `OUT_OF_BOUNDS_READ`，且各嵌套层级错误码一致、
  `path` 指向真实出错参数。

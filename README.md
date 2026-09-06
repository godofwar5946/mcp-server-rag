# MCP Server RAG

本轮工作台升级、数据库迁移、任务与评测接口及验证方式见 [升级说明](docs/PROFESSIONAL_UPGRADE.md)。

基于 Spring Boot 3.4.12、Spring AI MCP、PostgreSQL/pgvector、Ollama 和 Apache Tika 3.2.3 的 RAG 文档与 Java 代码服务。

## 目录与批量上传

- 管理页支持创建多级目录、重命名和递归删除。
- 目录树支持分支折叠、完整路径搜索、定位当前目录和一键全部收起，展开状态会保存在当前浏览器。
- 文件区提供面包屑和“上一级”导航；上传入口与当前目录整合，不再需要在目录树和独立上传区之间来回滚动。
- 支持一次选择多个文件并上传到指定目录。
- 文件保存后进入持久化索引队列，可在任务中心查看进度、取消或重试；上传提交成功与索引发布成功分别显示。
- 支持选择整个本地文件夹，后端依据 `webkitRelativePath` 自动创建多级目录并保留原结构。
- 浏览器会按每批最多 20 个文件、约 80MB 顺序提交，避免大型项目触发 multipart part 数量或请求大小限制。
- 同名文件只在同一目录内覆盖，不同目录可保存同名文件。
- 文件可在目录之间移动；递归删除目录时会同步删除文件及其向量切片。
- 移动文件时使用可按完整路径搜索的目标目录选择器，适合层级较深的项目目录。
- 选择具体目录后可导出 ZIP；压缩包包含所选目录本身、全部下级目录、空目录以及数据库中保存的原始文件内容。
- MCP 检索结果中的来源名称包含目录路径，便于区分不同目录中的同名文件。

目录导出 API：

```text
GET /api/folders/{folderId}/export
Response: application/zip
```

## 业务与代码分类检索

- 文件使用独立的 `knowledge_type` 分类，不占用保存 MIME 类型的 `content_type`。
- 类型支持 `ALL`、`BUSINESS`、`CODE`；新文件继承目录或上级目录的默认分类，没有配置时为 `ALL`。
- `ALL` 表示不限制分类，会同时参加业务与代码分类检索；普通 `rag.search` / `rag.ask` 始终查询全部类型。
- 管理页选择具体目录后，可点击“设置检索类型”批量修改当前目录，并可选择是否包含所有下级目录。
- 分类直接保存在 `rag_file`，修改后立即影响查询，不需要重新解析、切片或向量化。
- `business.search` 检索 `BUSINESS + ALL`；`code.search` 检索 `CODE + ALL` 中的 Java 和 XML 代码切片。

目录批量分类 API：

```text
PUT /api/folders/{folderId}/knowledge-type
Body: {"knowledgeType":"BUSINESS","recursive":true}
```

## Java 与 XML SQL 索引

- `.java` 使用 JDK 21 Compiler Tree API 做 AST 解析，不编译、不运行上传代码，也不要求项目依赖完整。
- 提取 Java 类、接口、枚举、Record、注解、构造方法和普通方法，并保存准确的起止行号与字符范围。
- `.xml` 会识别 MyBatis Mapper 的 `namespace`，以及 `select`、`insert`、`update`、`delete`、`sql` 节点 ID。
- Java 方法和 XML SQL 按结构生成向量切片；普通 XML 即使不是 Mapper，也会作为源码文本建立向量索引。
- 管理页可以搜索符号、查看带原始行号的类/方法/SQL，也可以通过“文件路径 + 行号”定位所在逻辑；超出阅读预算时会明确标记源码截断。
- 整个项目文件夹上传时会保留源码目录，并在前端忽略 `.git`、`.idea`、`.gradle`、`target`、`build`、`out` 和 `node_modules`。

代码管理 API：

```text
GET /api/code/symbols?query=UserService&category=CLASS
GET /api/code/symbols/{id}/source
GET /api/code/classes/source?className=com.example.UserService
GET /api/code/methods/source?className=UserService&methodName=findById&signature=findById(Long)
GET /api/code/sql/source?namespace=UserMapper&statementId=findById
GET /api/code/location?filePath=src/main/java/demo/UserService.java&line=42
GET /api/code/search?query=根据用户编号查询用户&topK=5
```

新增的 Spring AI MCP Tools：

```text
code.find
code.get_class
code.get_method
code.get_sql
code.get_by_line
business.search
code.search
rag.retrieve
kb.list
```

类名、方法名和 SQL ID 查询使用关系型索引保证精确定位；`business.search` 与 `code.search` 使用 pgvector 做分类型语义检索，并都支持可选的 `folderId` 项目范围。

## 管理端登录与远程访问

- 登录入口为 `/login.html`，使用表单登录和服务端 Session。未登录访问 `/` 或 `/index.html` 时，服务端直接跳转登录页，不发送工作台 HTML；工作台脚本、样式和管理 API 同样需要登录。
- 登录成功后进入工作台；退出或会话过期后返回登录页。工作台默认隐藏，验证 Session 后才显示；浏览器后退和切回标签页时重新验证登录状态。
- 登录成功后，浏览器只保存 HttpOnly 的 `JSESSIONID` Cookie，不保存用户名、密码或 Authorization 凭证；同一浏览器会自动恢复登录状态。
- Session 与 Cookie 当前有效期均为 12 小时；主动退出、超时、清除 Cookie 或重启服务后需要重新登录。不同电脑和不同浏览器各自登录一次是正常行为。
- 管理账号通过 `app.security.username/password` 配置，没有保存到数据库。密码从环境变量或被 Git 忽略的 `config/application-local.yml` 读取；当前本机配置已保留，其他环境需自行设置。
- 远程电脑访问前，需要把“远程电脑在服务端看到的来源 IP”加入 `app.security.ip-whitelist`，不要填写服务端自己的 IP。被拒绝时 JSON 响应中的 `clientIp` 就是应核对的地址。
- 修改账号、密码、白名单或 Session 配置后需要重启服务。MCP `/mcp` 仍允许匿名访问，不受管理端 Session 和 IP 白名单限制。

认证接口：

```text
GET  /api/auth/status
POST /api/auth/login
POST /api/auth/logout
```

## 数据库升级

已具有目录、代码索引和分类表的当前库，在启动时自动执行专业工作台迁移。更早版本的数据库需先补齐下面三个历史迁移：

```powershell
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f src/main/resources/db/folder-migration.sql
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f src/main/resources/db/code-index-migration.sql
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f src/main/resources/db/knowledge-type-migration.sql
```

三个历史迁移脚本均可重复执行。新数据库可在同一个 psql 连接内按顺序执行基础结构和工作台迁移：

```powershell
psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f src/main/resources/db/schema.sql -f src/main/resources/db/professional-migration.sql
```

工作台迁移继承连接中的 search_path，不主动切换 schema。上述手工脚本之后，首次启动还需开启 `app.database.migrate-schema=true`，完成模型管理迁移。详细启动配置见 [升级说明](docs/PROFESSIONAL_UPGRADE.md)。

## 管理端切换 Embedding 模型

登录后进入 **服务与模型 → Embedding 模型**，选择 Ollama 中已安装的模型，点击“验证模型”，再点击“切换并重建全部索引”。支持实际维度检测、自定义输出维度、查询指令、进度查看、取消和断点重试。

重建覆盖全库已发布的业务与代码切片。后台生成新模型向量，完成后统一切换；期间旧索引继续可用。结果持久化，重启后继续使用管理端选择的模型。首次部署需要数据库结构迁移，操作步骤与高维模型说明见 [模型管理说明](docs/EMBEDDING_MODELS.md)。

## 运行

```powershell
$env:JAVA_HOME='D:\work\java\jdk21'
$env:Path="D:\work\java\jdk21\bin;D:\work\java\Maven\apache-maven-3.8.4\bin;$env:Path"
mvn clean package
mvn spring-boot:run
```

启动后访问 `http://localhost:8082/`，MCP Streamable HTTP endpoint 为 `http://localhost:8082/mcp`。

Java AST 解析依赖完整 JDK 的 `jdk.compiler` 模块，因此运行服务时也必须使用 JDK 21，不能改用裁剪掉编译器模块的 JRE。

上传限制为单文件 50MB、单次请求 100MB；Tomcat 的 multipart part 上限通过 `server.tomcat.max-part-count: 2000` 配置。修改这些配置后需要重启服务。

主要配置见 `src/main/resources/application.yml`，完整项目约束见 `AGENT.md`。

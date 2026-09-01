# 智韵教声运行指南

## 最快启动

双击 `start_app.bat`，或在 PowerShell 中运行：

```powershell
.\launch.ps1
```

- 检测到 `config/application-local.yml`：启动数据库模式。
- 未检测到该文件：自动启动免数据库演示模式。

启动成功后访问：http://localhost:8081

需要安装 JDK 17 和 Maven 3.9+，并确保 `java`、`mvn` 命令已加入 PATH。

## 在 IDEA 中运行

1. 用 IDEA 打开项目根目录，并以 Maven 项目加载 `pom.xml`。
2. Project SDK 选择 JDK 17。
3. 直接运行 `com.a09.tts.TtsApplication`。

未指定 Profile 时默认进入 `nodb` 演示模式，可直接打开
`http://localhost:8081`，无需 MySQL。需要 Redis 时，在 IDEA 的 Run
Configuration → Environment variables 中加入：

```text
REDIS_ENABLED=true;REDIS_HOST=127.0.0.1;REDIS_PASSWORD=你的Redis密码;REDIS_DATABASE=9
```

## 数据库模式

先在 MySQL 中创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS zhiyunjiaos
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

复制：

```text
config/application-local.yml.example
```

并重命名为：

```text
config/application-local.yml
```

至少填写：

```properties
spring.datasource.username=你的MySQL用户名
spring.datasource.password=你的MySQL密码
jwt.secret=至少32位的随机字符串
```

该本地配置已被 `.gitignore` 忽略，不会提交密码或密钥。

启动数据库模式：

```powershell
.\launch.ps1 -Mode db
```

默认账号：`admin / 123456`

## 免数据库模式

未创建本地配置时会自动进入该模式，也可强制启动：

```powershell
.\launch.ps1 -Mode nodb
```

演示账号：

- `admin / admin123`
- `demo / demo123`

该模式的数据只保存在内存中，重启后注册用户会丢失。

## 可选功能

这些服务未配置时只影响对应功能，不影响基础页面和登录：

| 功能 | 默认地址或配置 |
|---|---|
| TTS 语音合成 | `http://127.0.0.1:9880/tts` |
| ASR 语音识别 | `http://127.0.0.1:9977/asr` |
| 阿里云方言合成 | `ALIYUN_NLS_APP_KEY`、`ALIYUN_AK_ID`、`ALIYUN_AK_SECRET` |
| 阿里云声音复刻 | 上述三项；参考音频须为公网可访问的 HTTPS URL |
| GPT-SoVITS 声音克隆 | `http://127.0.0.1:9880/tts`，启动脚本会自动检测并启动 |
| Moonshot/Kimi | 在本地配置中填写 `moonshot.api.key` |

### 阿里云 NLS Token 自动刷新

NLS Token 只有约 36–48 小时有效期，不能配置为永久 Token。项目会使用 RAM
AccessKey 调用 `CreateToken`，缓存结果并在过期前 5 分钟自动刷新：

```powershell
$env:ALIYUN_NLS_APP_KEY='你的 AppKey'
$env:ALIYUN_AK_ID='RAM 用户 AccessKey ID'
$env:ALIYUN_AK_SECRET='RAM 用户 AccessKey Secret'
.\launch.ps1
```

配置 RAM AccessKey 后不需要再设置 `ALIYUN_NLS_TOKEN`。该变量仅用于没有
AccessKey 时临时兼容手工 Token。不要把 AccessKey 写入仓库或提交到 Git。

## Redis（可选）

Redis用于缓存音色列表和保存30分钟的多轮对话会话。未启用时项目会自动使用进程内会话，
因此不影响本地开发，但音色查询不会缓存，重启后对话进度会丢失。

可使用Docker启动Redis：

```powershell
docker run -d --name zhiyun-redis -p 6379:6379 redis:7-alpine
```

然后在 `config/application-local.yml` 中配置：

```properties
app.redis.enabled=true
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.password=
spring.data.redis.database=9
```

也可以通过环境变量启用：

```powershell
$env:REDIS_ENABLED='true'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PASSWORD='你的Redis密码'
$env:REDIS_DATABASE='9'
.\launch.ps1
```

## 验证命令

```powershell
mvn -o test
mvn -o package
```

首次下载依赖时去掉 `-o`。

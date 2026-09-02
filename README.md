# 智韵教声

智韵教声是一个 Spring Boot 教学语音与课件处理应用，包含声音库、TTS/ASR、口语练习、课件生成、异步媒体任务和静态 Web 页面。

## 快速验证

要求 JDK 17 和 Maven 3.9+：

```powershell
mvn test
mvn -DskipTests package
```

本地免数据库启动：

```powershell
.\launch.ps1 -Mode nodb
```

访问 <http://localhost:8081>。MySQL、Docker Compose、Python ASR 和环境变量的完整说明见 [RUN.md](RUN.md)。

## 仓库结构

- `src/main/java`：Spring Boot 后端
- `src/main/resources/static`：已构建的前端静态产物
- `src/main/resources/db/migration`：Flyway migration
- `scripts/asr_server.py`：本地 FunASR 服务
- `docker-compose.yml`：MySQL、Redis 与后端

## 前端源码状态

仓库没有 `package.json`、前端锁文件、Vite/Vue 配置或组件源码，只有已经构建的 JavaScript/CSS bundle 和少量独立补丁脚本。因此：

- 当前静态页面可以由 Spring Boot 直接提供；
- 无法从本仓库执行 `npm ci` 或可复现的前端构建；
- 不应反编译 bundle 或伪造前端源码工程；
- 正式发布前应找回原始前端仓库及锁文件，并在 CI 中加入真实前端构建。

## 配置安全

真实数据库密码、JWT secret、Moonshot Key 和阿里云凭据只通过环境变量或已忽略的 `config/application-local.yml` 提供。仓库中的 `.env.example` 和示例配置只包含占位值。

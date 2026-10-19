# PLDT Chat Demo - Java Backend

Java 21 + Spring Boot 3.4.3 chatbot backend for PLDT Philippines.

## Requirements

- JDK 21 or newer
- Maven 3.6+

## Configuration

Set the following environment variables (or edit `src/main/resources/application.yml`):

```bash
# OpenAI-compatible chat model (required for /api/chatbot)
export OPENAI_BASE_URL=https://maas-api.cn-huabei-1.xf-yun.com/v2
export OPENAI_API_KEY=your-api-key
export OPENAI_MODEL=xopglm51

# iFlytek TTS/IST (required for /api/tts-auth and /api/ist-auth)
export XFYUN_TTS_APP_ID=your-app-id
export XFYUN_TTS_API_KEY=your-api-key
export XFYUN_TTS_API_SECRET=your-api-secret

# iFlytek lynxiao search (defaults are in application.yml, override if needed)
# export LYNXIAO_APP_ID=your-app-id
# export LYNXIAO_API_SECRET=your-api-secret
```

## Build

```bash
mvn clean package
```

The executable JAR will be at `target/phil-demo.jar`.

## Run

Development mode (with Maven):
```bash
mvn spring-boot:run
```

Production mode (standalone JAR):
```bash
java -jar target/phil-demo.jar
```

Override port:
```bash
PORT=8080 java -jar target/phil-demo.jar
```

The server listens on `http://127.0.0.1:3000` by default.

## API Endpoints

- `GET /health` - Health check (`{"status":"ok"}`)
- `GET /api/tts-auth?language=en` - iFlytek TTS WebSocket signed URL
- `GET /api/ist-auth?language=en` - iFlytek IST WebSocket signed URL
- `POST /api/search` - PLDT knowledge base search proxy
- `POST /api/chatbot` - SSE streaming chatbot endpoint
- `GET /static/*` - Static assets

## Implementation Notes

This Java implementation provides:

- **Configuration**: Environment variables mapped to `application.yml` with `@ConfigurationProperties`
- **iFlytek signatures**: HMAC-SHA256 signing logic
- **Lynxiao search**: Two-step route + search with HMAC auth
- **Chatbot SSE**: Query planning → search → streaming answer generation → citation validation
- **HTTP client**: Java 21 `HttpClient`
- **JSON**: Jackson (Spring Boot default)

No external dependencies beyond Spring Boot starters; no Redis, no database, no message queue.
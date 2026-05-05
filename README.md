# Cortex

**[Live Demo](https://cortex-media.in)**

A media intelligence platform. Upload video or audio — Cortex automatically chunks it, transcribes the audio (Groq Whisper), analyzes the video (Gemini), generates embeddings, and indexes everything into a searchable vector database. Then search across all your media using natural language.

## Architecture

```mermaid
graph LR
    Browser([Browser]) -->|HTTPS| SWAG[Nginx / SWAG]

    SWAG --> Frontend[Next.js]
    SWAG --> Gateway[API Gateway]

    Gateway --- Redis[(Redis)]
    Gateway --> Ingestion[Ingestion Service]
    Gateway --> RAG[RAG Orchestration]

    PubSub([GCS Pub/Sub]) -->|Webhook| Gateway

    Ingestion -->|cortex.file.ingested.media| Kafka{{Kafka}}
    Kafka -->|consume| MediaProc[Media Processing]
    MediaProc -->|media-chunk-uploaded| Kafka
    Kafka -->|consume| RAG

    Ingestion ---|cortex.pipeline.events| Kafka

    Ingestion --> DB[(PostgreSQL + pgvector)]
    MediaProc --> DB
    RAG --> DB

    Ingestion --> GCS[(Google Cloud Storage)]
    MediaProc --> GCS
    RAG --> GCS

    RAG --> Groq[Groq Whisper API]
    RAG --> Gemini[Google Gemini API]
```

## How It Works

1. **Upload** — User selects a file. The frontend gets a signed GCS URL from the Ingestion service and uploads directly to Google Cloud Storage.
2. **Webhook** — GCS triggers a Pub/Sub notification to the Ingestion service, which marks the file as uploaded and publishes a Kafka event.
3. **Chunking** — Media Processing consumes the event, streams the file from GCS, and splits it into 60-second chunks (video `.mp4` + audio `.wav`) using FFmpeg.
4. **AI Analysis** — RAG Orchestration consumes each chunk and runs two tasks in parallel:
   - **Vision**: Sends the video chunk to Gemini for visual scene description.
   - **Transcription**: Sends the audio chunk to Groq Whisper for speech-to-text.
5. **Embedding** — The vision summary and transcript are combined and embedded into a 768-dimensional vector using Gemini Embeddings, then stored in PostgreSQL (pgvector).
6. **Search** — Users search with natural language. The query is embedded and matched against stored chunks using hybrid search (semantic + full-text) with Reciprocal Rank Fusion.

## Components

| Service | Tech | Role |
|---|---|---|
| **Frontend** | Next.js 16, React 19, Tailwind, shadcn | UI — file management, pipeline timeline (SSE), search |
| **API Gateway** | Spring Cloud Gateway, Redis | OAuth2 (Google), session management, JWT relay, routing |
| **Ingestion** | Spring Boot, Kafka, GCS | File metadata CRUD, webhooks, SSE broadcast, pipeline event tracking |
| **Media Processing** | Spring Boot, FFmpeg, Kafka | Streams media from GCS, chunks with FFmpeg, uploads chunks back to GCS |
| **RAG Orchestration** | Spring Boot, Gemini, Groq, Spring AI | Vision analysis, transcription, embedding generation, hybrid search |
| **PostgreSQL** | pgvector | Stores file metadata, media chunks, embeddings, pipeline events |
| **Kafka** | Apache Kafka | Async event bus between services |
| **Redis** | Redis 8 | Gateway session store |

## Stack

- **Backend**: Java 21, Spring Boot 3.5, Spring Cloud Gateway, Spring AI, Spring Kafka
- **Frontend**: Next.js 16, React 19, TypeScript, Tailwind CSS v4, Framer Motion
- **AI**: Groq (Whisper Large v3), Google Gemini 2.5 Flash (Vision), Gemini Embeddings (768-dim)
- **Infra**: Docker Compose, Nginx (SWAG), GCS, Cloud SQL, Aiven Kafka (mTLS)
- **Deployment**: GCP e2-standard-2 VM, manual `deploy.sh`

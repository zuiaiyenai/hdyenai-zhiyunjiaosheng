# Heavy task and HTTP decoupling

## Phase 4 outcome

HTTP requests now perform only authentication, upload validation, object-storage staging and
bounded task admission for the main long-running media flows. The response is `202 Accepted`
with `taskId`; the client polls `GET /api/tasks/{id}` and retrieves a successful object result
from `GET /api/tasks/{id}/result`.

The upload itself is still part of the request. GPT-SoVITS, FunASR, Moonshot, FFmpeg and
ffprobe execution happens after the HTTP response on the bounded task executor.

## Converted entry points

| Entry point | Worker operation | Result |
|---|---|---|
| `POST /courseware/projects` | Moonshot PPT extraction and initial script generation | project id in task state |
| `POST /courseware/projects/{id}/optimize` and `/optimize/tasks` | Moonshot script optimization | project id |
| `POST /courseware/projects/{id}/audio` and `/audio/tasks` | TTS narration generation | project id |
| `POST /courseware/projects/{id}/video` and `/video/tasks` | POI render plus FFmpeg video generation | project id |
| `POST /asr/transcribe` | FunASR file transcription | owner-scoped JSON object |
| `POST /accessibility/voice-note` | FunASR transcription plus persistent note creation | owner-scoped JSON object |
| `POST /speaking_practice/evaluate` | FunASR evaluation and history persistence | owner-scoped JSON object |
| `POST /video_voice_swap/subtitles` | FFmpeg extraction, FunASR and timeline generation | owner-scoped JSON object |
| `POST /video_voice_swap/process` | FFmpeg, optional FunASR, TTS and final mux | owner-scoped MP4 object |
| `POST /sound_clone/upload` | local GPT-SoVITS streaming synthesis | owner-scoped WAV object |
| `POST /courseware/summary` | Moonshot PPT extraction and summary generation | owner-scoped text object |

Both the maintained Vue source and the Spring-served static UI use task polling. Polling starts
at one second, backs off to five seconds, stops at terminal state and has a 16-minute client
deadline. It is not a zero-delay busy loop.

## Input and result lifecycle

- Multipart files are validated before admission and staged under an owner-derived object key.
- Workers materialize request-scoped local copies only when a library or external process needs
  a filesystem path.
- Successful JSON, text, audio and video results are stored as managed objects. Result reads
  first verify both task ownership and object ownership.
- A completion hook removes staged input after success, failure, timeout, cancellation or local
  executor rejection. A running cancellation defers cleanup until the worker action has actually
  exited, so an interrupt-ignoring external library cannot race with input deletion. Voice-note
  audio is written to its permanent key before the staging object is removed.
- Duplicate task admission removes the newly staged duplicate input and reuses the active task.

## Deliberately retained synchronous paths

- `/voice/synthesize`, `/voice/stream`, dialect TTS and Aliyun cloned-voice synthesis return
  short audio directly. Phase 6 must add resource-specific concurrency limits; Phase 12 must
  determine safe concurrency rather than assuming it.
- `/accessibility/read-ppt` performs bounded local extraction only (upload limit and slide-count
  limit already apply). It does not call Moonshot.
- WebSocket ASR is inherently connection-oriented and is not represented as a file task.
  Connection quotas, node-drain behavior and resource isolation remain Phase 6/10 work.
- Cloud control calls such as Aliyun voice enrollment and short study-summary requests keep their
  existing HTTP contract; their timeout, supplier quota and failure behavior remain separately
  measured L5 cloud operations.

## Evidence boundary and remaining risks

- Phase 4 reuses the current bounded executor and database-backed atomic task state. The Java
  action is still a captured in-process callback. A process restart cannot reconstruct it;
  durable payloads, worker claim, retry, heartbeat and stale recovery belong to Phase 5.
- All heavy task types still share one executor. Head-of-line blocking and resource-specific
  concurrency are not solved until Phase 6.
- The video implementation still materializes the completed MP4 as `byte[]` before storing it.
  HTTP thread retention is removed, but heap amplification remains to be removed and measured.
- Object results currently have no retention expiry/reconciliation policy. That is required
  before production capacity can be claimed.
- This phase is functional decoupling evidence, not throughput, soak, failover or production
  capacity evidence.
